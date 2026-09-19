#define _GNU_SOURCE
#include "net.h"
#include "audio.h"
#include "protocol.h"

#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define SESSION_TIMEOUT_NS  2000000000ULL
#define MAX_FRAMES_PER_PKT  4096
#define PENDING_MAX         128          /* lost packets we may still recover */
#define NACK_RETRY_NS       10000000ULL  /* re-ask every 10 ms ... */
#define NACK_MAX_TRIES      4            /* ... at most this often */

/* A packet that was missing when its successor arrived. Its slot in the
 * jitter buffer holds silence until a retransmission lands, or it is played. */
struct pending {
    uint16_t seq;
    uint8_t  tries;
    bool     used;
    uint64_t pos;          /* stream position of the silence */
    uint64_t last_nack_ns;
    long     dump_off;     /* byte offset of the silence in the WAV dump, -1 if none */
};

struct net {
    int sock;
    int port;
    pthread_t thread;
    _Atomic bool running;
    _Atomic bool listening;

    pthread_mutex_t lock;        /* protects the fields below */
    struct net_stats st;
    bool have_session;
    struct sockaddr_in peer;     /* where the current sender's packets come from */
    struct pending pending[PENDING_MAX];
    uint16_t next_seq;
    uint64_t last_rx_ns;
    uint64_t session_start_ns;
    uint64_t last_report_ns;
    /* bitrate window */
    uint64_t win_start_ns, win_bytes;
    /* jitter */
    int64_t  last_transit;
    uint64_t last_rx_pkt_ns;     /* arrival of the previous audio packet */
    double   jitter;             /* in microseconds */
    /* wav dump */
    FILE    *dump;
    uint64_t dump_frames;
    char     dump_path[200];
};

static struct net N;

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

/* ---- WAV dump -------------------------------------------------------------- */

static void wav_write_u32(FILE *f, uint32_t v) { uint8_t b[4] = { v, v >> 8, v >> 16, v >> 24 }; fwrite(b, 1, 4, f); }
static void wav_write_u16(FILE *f, uint16_t v) { uint8_t b[2] = { v, v >> 8 }; fwrite(b, 1, 2, f); }

static void wav_write_header(FILE *f, int rate, int ch, uint64_t frames)
{
    uint32_t data_bytes = (uint32_t)(frames * ch * 4);
    fseek(f, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, f); wav_write_u32(f, 36 + data_bytes);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); wav_write_u32(f, 16);
    wav_write_u16(f, 3);                       /* IEEE float */
    wav_write_u16(f, (uint16_t)ch);
    wav_write_u32(f, (uint32_t)rate);
    wav_write_u32(f, (uint32_t)(rate * ch * 4));
    wav_write_u16(f, (uint16_t)(ch * 4));
    wav_write_u16(f, 32);
    fwrite("data", 1, 4, f); wav_write_u32(f, data_bytes);
}

static void dump_close_locked(void)
{
    if (!N.dump)
        return;
    if (N.st.cfg.rate > 0)
        wav_write_header(N.dump, N.st.cfg.rate, N.st.cfg.channels, N.dump_frames);
    fclose(N.dump);
    N.dump = NULL;
    N.dump_path[0] = 0;
    N.st.dump_path[0] = 0;
}

int net_set_dump(const char *path)
{
    pthread_mutex_lock(&N.lock);
    dump_close_locked();
    int rc = 0;
    if (path && *path) {
        N.dump = fopen(path, "wb");
        if (!N.dump) {
            rc = -errno;
        } else {
            N.dump_frames = 0;
            snprintf(N.dump_path, sizeof(N.dump_path), "%s", path);
            snprintf(N.st.dump_path, sizeof(N.st.dump_path), "%s", path);
            if (N.st.cfg.rate > 0)
                wav_write_header(N.dump, N.st.cfg.rate, N.st.cfg.channels, 0);
        }
    }
    pthread_mutex_unlock(&N.lock);
    return rc;
}

/* ---- packet handling ------------------------------------------------------- */

static void convert_to_float(const uint8_t *src, int fmt, int samples, float *dst)
{
    switch (fmt) {
    case HFS_FMT_S16LE:
        for (int i = 0; i < samples; i++) {
            int16_t v = (int16_t)(src[2 * i] | src[2 * i + 1] << 8);
            dst[i] = (float)v / 32768.f;
        }
        break;
    case HFS_FMT_S24LE:
        for (int i = 0; i < samples; i++) {
            int32_t v = (int32_t)((uint32_t)src[3 * i] << 8 | (uint32_t)src[3 * i + 1] << 16 |
                                  (uint32_t)src[3 * i + 2] << 24) >> 8;
            dst[i] = (float)v / 8388608.f;
        }
        break;
    case HFS_FMT_F32LE:
        memcpy(dst, src, (size_t)samples * 4);
        break;
    }
}

/* ---- retransmission ------------------------------------------------------- */

static void send_nack_locked(const uint16_t *seqs, int n)
{
    uint8_t msg[8 + 2 * HFS_NACK_MAX];
    memcpy(msg, HFS_NACK_MSG, 8);
    for (int i = 0; i < n; i++) {
        msg[8 + 2 * i] = (uint8_t)seqs[i];
        msg[9 + 2 * i] = (uint8_t)(seqs[i] >> 8);
    }
    sendto(N.sock, msg, 8 + 2 * (size_t)n, MSG_DONTWAIT, (const struct sockaddr *)&N.peer, sizeof(N.peer));
    N.st.nacks++;
}

static void pending_clear_locked(void)
{
    memset(N.pending, 0, sizeof(N.pending));
}

/* Records `count` packets from `seq` as missing at stream position `pos`
 * and asks the sender for them straight away. */
static void pending_add_locked(uint16_t seq, int count, uint64_t pos, int frames, long dump_off, int nsamples, uint64_t now)
{
    uint16_t ask[HFS_NACK_MAX];
    int n = 0;
    for (int i = 0; i < count; i++) {
        struct pending *slot = NULL;
        for (int j = 0; j < PENDING_MAX; j++)
            if (!N.pending[j].used) { slot = &N.pending[j]; break; }
        if (!slot)
            break;
        slot->used = true;
        slot->seq = (uint16_t)(seq + i);
        slot->tries = 1;
        slot->pos = pos + (uint64_t)i * (uint64_t)frames;
        slot->last_nack_ns = now;
        slot->dump_off = dump_off < 0 ? -1 : dump_off + (long)i * nsamples * (long)sizeof(float);
        if (n < HFS_NACK_MAX)
            ask[n++] = slot->seq;
    }
    if (n > 0)
        send_nack_locked(ask, n);
}

/* Re-asks for packets still worth having; gives up after a few tries
 * (by then their slot has long been played). */
static void pending_tick_locked(uint64_t now)
{
    uint16_t ask[HFS_NACK_MAX];
    int n = 0;
    for (int j = 0; j < PENDING_MAX; j++) {
        struct pending *p = &N.pending[j];
        if (!p->used || now - p->last_nack_ns < NACK_RETRY_NS)
            continue;
        if (p->tries >= NACK_MAX_TRIES) {
            p->used = false;
            continue;
        }
        p->tries++;
        p->last_nack_ns = now;
        if (n < HFS_NACK_MAX)
            ask[n++] = p->seq;
    }
    if (n > 0)
        send_nack_locked(ask, n);
}

static struct pending *pending_find_locked(uint16_t seq)
{
    for (int j = 0; j < PENDING_MAX; j++)
        if (N.pending[j].used && N.pending[j].seq == seq)
            return &N.pending[j];
    return NULL;
}

static void end_session_locked(void)
{
    if (N.have_session) {
        N.have_session = false;
        N.st.active = false;
        N.st.phone_volume = -1;
        audio_close();
    }
}

static void handle_audio(const uint8_t *pkt, unsigned len, const struct sockaddr_in *from, uint64_t rx_ns)
{
    struct hfs_header h;
    if (hfs_parse_header(pkt, len, &h) != 0 || h.frames > MAX_FRAMES_PER_PKT) {
        pthread_mutex_lock(&N.lock);
        N.st.invalid++;
        pthread_mutex_unlock(&N.lock);
        return;
    }
    static float samples[MAX_FRAMES_PER_PKT * 2];
    int nsamples = (int)h.frames * h.channels;
    convert_to_float(pkt + HFS_HDR_SIZE, h.format, nsamples, samples);

    pthread_mutex_lock(&N.lock);
    bool new_session = !N.have_session ||
        N.st.cfg.rate != (int)h.rate || N.st.cfg.channels != h.channels || N.st.cfg.format != h.format;
    if (new_session) {
        struct audio_config cfg = { .rate = (int)h.rate, .channels = h.channels, .format = h.format };
        if (N.dump) {
            /* format changed under a running dump: restart the file */
            char path[200];
            snprintf(path, sizeof(path), "%s", N.dump_path);
            dump_close_locked();
            N.dump = fopen(path, "wb");
            if (N.dump) {
                N.dump_frames = 0;
                snprintf(N.dump_path, sizeof(N.dump_path), "%s", path);
                snprintf(N.st.dump_path, sizeof(N.st.dump_path), "%s", path);
                wav_write_header(N.dump, cfg.rate, cfg.channels, 0);
            }
        }
        audio_configure(&cfg);
        N.have_session = true;
        N.st.cfg = cfg;
        N.next_seq = h.seq;
        N.session_start_ns = rx_ns;
        N.st.packets = N.st.bytes = N.st.lost = N.st.late = 0;
        N.st.nacks = N.st.recovered = 0;
        N.last_report_ns = rx_ns;
        pending_clear_locked();
        N.win_start_ns = rx_ns; N.win_bytes = 0;
        N.st.phone_volume = -1;
        N.jitter = 0; N.last_transit = 0; N.last_rx_pkt_ns = 0;
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &from->sin_addr, ip, sizeof(ip));
        snprintf(N.st.sender, sizeof(N.st.sender), "%s:%u", ip, ntohs(from->sin_port));
        fprintf(stderr, "session from %s: %u Hz, %u ch, %s, %u frames/packet\n",
                N.st.sender, h.rate, h.channels, hfs_format_name(h.format), h.frames);
    }
    N.st.frames_per_packet = (int)h.frames;
    N.st.active = true;
    N.last_rx_ns = rx_ns;
    N.peer = *from;

    int16_t diff = (int16_t)(h.seq - N.next_seq);
    if (diff < 0) {
        /* A packet we already substituted silence for: a retransmission we
         * asked for, or just late. Either way it is still worth playing if
         * its slot has not been reached yet. */
        struct pending *p = pending_find_locked(h.seq);
        if (p) {
            p->used = false;
            if (audio_patch(p->pos, samples, (int)h.frames)) {
                N.st.recovered++;
                N.st.lost--;
                if (N.dump && p->dump_off >= 0) {
                    long end = ftell(N.dump);
                    fseek(N.dump, p->dump_off, SEEK_SET);
                    fwrite(samples, sizeof(float), (size_t)nsamples, N.dump);
                    fseek(N.dump, end, SEEK_SET);
                }
                pthread_mutex_unlock(&N.lock);
                return;
            }
        }
        N.st.late++;
        pthread_mutex_unlock(&N.lock);
        return;
    }
    if (diff > 0) {
        if (diff < 200) {
            N.st.lost += diff;
            uint64_t pos = audio_write_pos();
            long dump_off = N.dump ? ftell(N.dump) : -1;
            audio_push_silence(diff * (int)h.frames);
            if (N.dump) {
                static const float zeros[MAX_FRAMES_PER_PKT * 2];
                for (int i = 0; i < diff; i++)
                    fwrite(zeros, sizeof(float), (size_t)nsamples, N.dump);
                N.dump_frames += (uint64_t)diff * h.frames;
            }
            if (audio_write_pos() != pos)   /* silence really went in: recoverable */
                pending_add_locked(N.next_seq, diff, pos, (int)h.frames, dump_off, nsamples, rx_ns);
        } else {
            /* the sender restarted: resynchronise instead of padding seconds of silence */
            audio_flush();
        }
    }
    N.next_seq = (uint16_t)(h.seq + 1);

    N.st.packets++;
    N.st.bytes += len;
    N.win_bytes += len;
    if (rx_ns - N.win_start_ns >= 1000000000ULL) {
        N.st.bitrate_kbps = (double)N.win_bytes * 8.0 / ((rx_ns - N.win_start_ns) / 1e6);
        N.win_start_ns = rx_ns;
        N.win_bytes = 0;
    }
    /* RFC 3550 inter-arrival jitter using the sender's timestamp (both in µs).
     * The sender releases packets in small groups, so packets that arrive on
     * the heels of another (< 1 ms) carry the group's hold, not the network's
     * delay; only the first packet of each bunch is a clean sample. */
    int64_t transit = (int64_t)(rx_ns / 1000) - (int64_t)h.ts_us;
    bool leader = N.last_rx_pkt_ns == 0 || rx_ns - N.last_rx_pkt_ns >= 1000000ULL;
    if (leader) {
        if (N.last_transit != 0) {
            double d = (double)(transit - N.last_transit);
            if (d < 0) d = -d;
            N.jitter += (d - N.jitter) / 16.0;
        }
        N.last_transit = transit;
    }
    N.last_rx_pkt_ns = rx_ns;
    N.st.jitter_ms = N.jitter / 1000.0;
    N.st.session_seconds = (rx_ns - N.session_start_ns) / 1e9;

    if (N.dump) {
        fwrite(samples, sizeof(float), (size_t)nsamples, N.dump);
        N.dump_frames += h.frames;
        N.st.dump_frames = N.dump_frames;
    }
    pending_tick_locked(rx_ns);
    if (rx_ns - N.last_report_ns >= 1000000000ULL) {
        N.last_report_ns = rx_ns;
        struct audio_stats a;
        audio_get_stats(&a);
        char msg[128];
        int m = snprintf(msg, sizeof(msg), "%s %.2f %" PRIu64 " %" PRIu64 " %" PRIu64 " %.1f", HFS_REPORT_MSG,
                         N.st.jitter_ms, N.st.lost, N.st.recovered, a.underruns,
                         a.cfg.rate ? 1000.0 * a.fill_frames / a.cfg.rate : 0.0);
        sendto(N.sock, msg, (size_t)m, MSG_DONTWAIT, (const struct sockaddr *)&N.peer, sizeof(N.peer));
    }
    pthread_mutex_unlock(&N.lock);

    audio_push(samples, (int)h.frames);
}

static void handle_discover(const struct sockaddr_in *from)
{
    char host[64] = "receiver";
    gethostname(host, sizeof(host) - 1);
    char reply[96];
    int n = snprintf(reply, sizeof(reply), "%s %s", HFS_HERE_MSG, host);
    sendto(N.sock, reply, (size_t)n, 0, (const struct sockaddr *)from, sizeof(*from));
}

static void *rx_thread(void *arg)
{
    (void)arg;
    /* Best effort real-time priority; falls back silently. */
    struct sched_param sp = { .sched_priority = 20 };
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) != 0)
        if (nice(-10) == -1) { /* not permitted, fine */ }

    static uint8_t pkt[2048];
    while (atomic_load(&N.running)) {
        struct sockaddr_in from;
        socklen_t flen = sizeof(from);
        ssize_t len = recvfrom(N.sock, pkt, sizeof(pkt), 0, (struct sockaddr *)&from, &flen);
        uint64_t t = now_ns();
        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                pthread_mutex_lock(&N.lock);
                if (N.have_session && t - N.last_rx_ns > SESSION_TIMEOUT_NS) {
                    fprintf(stderr, "sender timed out\n");
                    end_session_locked();
                }
                pthread_mutex_unlock(&N.lock);
                continue;
            }
            perror("recvfrom");
            break;
        }
        if (len >= (ssize_t)strlen(HFS_DISCOVER_MSG) && memcmp(pkt, HFS_DISCOVER_MSG, strlen(HFS_DISCOVER_MSG)) == 0) {
            handle_discover(&from);
            continue;
        }
        if (len >= (ssize_t)strlen(HFS_VOLUME_MSG) + 2 && memcmp(pkt, HFS_VOLUME_MSG, strlen(HFS_VOLUME_MSG)) == 0) {
            pkt[len < (ssize_t)sizeof(pkt) ? len : (ssize_t)sizeof(pkt) - 1] = 0;
            double v = atof((const char *)pkt + strlen(HFS_VOLUME_MSG));
            if (v >= 0.0 && v <= 1.0) {
                audio_set_phone_volume(v);
                pthread_mutex_lock(&N.lock);
                N.st.phone_volume = v;
                pthread_mutex_unlock(&N.lock);
            }
            continue;
        }
        if (len >= (ssize_t)strlen(HFS_BYE_MSG) && memcmp(pkt, HFS_BYE_MSG, strlen(HFS_BYE_MSG)) == 0) {
            pthread_mutex_lock(&N.lock);
            fprintf(stderr, "sender said goodbye\n");
            end_session_locked();
            pthread_mutex_unlock(&N.lock);
            continue;
        }
        handle_audio(pkt, (unsigned)len, &from, t);
    }
    pthread_mutex_lock(&N.lock);
    end_session_locked();
    pthread_mutex_unlock(&N.lock);
    return NULL;
}

/* ---- lifecycle ------------------------------------------------------------- */

int net_start(int port)
{
    if (atomic_load(&N.running))
        return 0;
    static bool inited;
    if (!inited) {
        pthread_mutex_init(&N.lock, NULL);
        inited = true;
    }
    N.sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (N.sock < 0)
        return -errno;
    int one = 1, rcvbuf = 4 << 20;
    setsockopt(N.sock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(N.sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
    struct timeval tv = { .tv_sec = 0, .tv_usec = 250000 };
    setsockopt(N.sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    struct sockaddr_in addr = { .sin_family = AF_INET, .sin_port = htons((uint16_t)port),
                                .sin_addr.s_addr = htonl(INADDR_ANY) };
    if (bind(N.sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        int e = errno;
        close(N.sock);
        N.sock = -1;
        return -e;
    }
    N.port = port;
    pthread_mutex_lock(&N.lock);
    N.st.port = port;
    N.st.listening = true;
    pthread_mutex_unlock(&N.lock);
    atomic_store(&N.running, true);
    if (pthread_create(&N.thread, NULL, rx_thread, NULL) != 0) {
        atomic_store(&N.running, false);
        close(N.sock);
        return -1;
    }
    return 0;
}

void net_stop(void)
{
    if (!atomic_load(&N.running))
        return;
    atomic_store(&N.running, false);
    pthread_join(N.thread, NULL);
    close(N.sock);
    N.sock = -1;
    pthread_mutex_lock(&N.lock);
    N.st.listening = false;
    N.st.active = false;
    dump_close_locked();
    pthread_mutex_unlock(&N.lock);
}

bool net_running(void)
{
    return atomic_load(&N.running);
}

void net_get_stats(struct net_stats *out)
{
    pthread_mutex_lock(&N.lock);
    *out = N.st;
    pthread_mutex_unlock(&N.lock);
}
