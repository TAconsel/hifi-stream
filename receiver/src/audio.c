#define _GNU_SOURCE
#include "audio.h"
#include "protocol.h"

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <spa/utils/result.h>
#include <spa/param/props.h>

#include <math.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RING_SECONDS      2      /* capacity of the jitter buffer */
#define XFADE_MAX         64     /* frames per crossfaded drop/insert */
#define EMA_SECONDS       0.4    /* time constant of the fill-level average */
#define UNDERRUN_REBUFFER 0.25   /* seconds of continuous underrun before re-prebuffering */
#define HARD_LIMIT_MS     100.0  /* fill above min+target+this => hard resync */
#define PREBUFFER_EXTRA_MS 20.0  /* headroom above the margin when starting */
#define MIN_WINDOW_SECONDS 5.0   /* bucket of the minimum-fill tracker (memory 5-10 s) */
#define ADJUST_GAP_FRAMES (XFADE_MAX * 200) /* frames between corrections: <= 0.5 % speed change */

struct audio {
    struct audio_options opts;
    struct pw_thread_loop *loop;
    struct pw_stream *stream;
    struct spa_hook listener;

    struct audio_config cfg;
    int   stride;              /* floats per frame */

    /* Ring buffer of interleaved float frames. Positions are monotonically
     * increasing frame counters, wrapped with % on access. */
    float *ring;
    size_t cap_frames;
    _Atomic uint64_t wpos;     /* written by the network thread */
    _Atomic uint64_t rpos;     /* written by the PipeWire RT thread */

    _Atomic int state;
    _Atomic int flush_req;      /* producer asks the consumer to discard the buffer */
    _Atomic int target_frames;  /* requested target from the UI */
    _Atomic int eff_target;     /* effective target after clamping */
    _Atomic int quantum;
    _Atomic int graph_rate;
    _Atomic int min_fill;       /* windowed minimum fill (what the controller regulates) */
    _Atomic int avg_fill;
    _Atomic uint64_t underruns, overflows, drops, inserts, resyncs;
    _Atomic uint32_t peak_bits[2];
    double target_ms;
    double phone_volume;        /* -1 = not forwarded */

    /* RT-thread private */
    double  ema_fill;
    int64_t win_min, prev_min;  /* minimum fill in the current and previous window */
    int64_t win_frames;
    int64_t underrun_run;       /* frames of continuous silence emitted */
    int64_t since_adjust;       /* frames rendered since the last drop/insert */
    float  *scratch;            /* quantum + XFADE_MAX frames */
    size_t  scratch_frames;
};

static struct audio A;

static void apply_volume_locked(void);

static inline uint64_t ring_fill(void)
{
    uint64_t w = atomic_load_explicit(&A.wpos, memory_order_acquire);
    uint64_t r = atomic_load_explicit(&A.rpos, memory_order_acquire);
    return w > r ? w - r : 0;
}

/* ---- producer ------------------------------------------------------------ */

void audio_push(const float *samples, int frames)
{
    if (!A.ring || frames <= 0)
        return;
    uint64_t fill = ring_fill();
    if (fill + (uint64_t)frames > A.cap_frames) {
        atomic_fetch_add(&A.overflows, 1);
        return;
    }
    uint64_t w = atomic_load_explicit(&A.wpos, memory_order_relaxed);
    size_t pos = (size_t)(w % A.cap_frames);
    size_t first = A.cap_frames - pos;
    if (first > (size_t)frames)
        first = frames;
    memcpy(A.ring + pos * A.stride, samples, first * A.stride * sizeof(float));
    if ((size_t)frames > first)
        memcpy(A.ring, samples + first * A.stride, (frames - first) * A.stride * sizeof(float));

    for (int c = 0; c < A.cfg.channels && c < 2; c++) {
        float peak = 0.f;
        for (int i = 0; i < frames; i++) {
            float v = fabsf(samples[i * A.stride + c]);
            if (v > peak) peak = v;
        }
        uint32_t bits; memcpy(&bits, &peak, 4);
        uint32_t old = atomic_load(&A.peak_bits[c]);
        float oldf; memcpy(&oldf, &old, 4);
        while (peak > oldf && !atomic_compare_exchange_weak(&A.peak_bits[c], &old, bits))
            memcpy(&oldf, &old, 4);
    }
    atomic_store_explicit(&A.wpos, w + frames, memory_order_release);
}

void audio_push_silence(int frames)
{
    if (!A.ring || frames <= 0)
        return;
    uint64_t fill = ring_fill();
    if (fill + (uint64_t)frames > A.cap_frames) {
        atomic_fetch_add(&A.overflows, 1);
        return;
    }
    uint64_t w = atomic_load_explicit(&A.wpos, memory_order_relaxed);
    for (int i = 0; i < frames; i++) {
        size_t pos = (size_t)((w + i) % A.cap_frames);
        memset(A.ring + pos * A.stride, 0, A.stride * sizeof(float));
    }
    atomic_store_explicit(&A.wpos, w + frames, memory_order_release);
}

uint64_t audio_write_pos(void)
{
    return atomic_load_explicit(&A.wpos, memory_order_acquire);
}

bool audio_patch(uint64_t at, const float *samples, int frames)
{
    if (!A.ring || frames <= 0)
        return false;
    uint64_t r = atomic_load_explicit(&A.rpos, memory_order_acquire);
    uint64_t w = atomic_load_explicit(&A.wpos, memory_order_acquire);
    if (at < r || at + (uint64_t)frames > w)
        return false;             /* already played (or flushed away) */
    size_t pos = (size_t)(at % A.cap_frames);
    size_t first = A.cap_frames - pos;
    if (first > (size_t)frames)
        first = frames;
    memcpy(A.ring + pos * A.stride, samples, first * A.stride * sizeof(float));
    if ((size_t)frames > first)
        memcpy(A.ring, samples + first * A.stride, (frames - first) * A.stride * sizeof(float));
    /* If the reader overtook us meanwhile the packet was partly late; the
     * consumer copied whatever was there, which is at worst the silence. */
    return atomic_load_explicit(&A.rpos, memory_order_acquire) <= at;
}

void audio_flush(void)
{
    /* Only the consumer may move rpos, so the discard is done by the RT
     * thread on its next cycle. */
    if (!A.ring)
        return;
    atomic_store(&A.flush_req, 1);
    if (atomic_load(&A.state) == AUDIO_PLAYING)
        atomic_store(&A.state, AUDIO_PREBUFFER);
}

/* ---- consumer (PipeWire RT thread) --------------------------------------- */

static inline void ring_read(float *dst, int frames)
{
    uint64_t r = atomic_load_explicit(&A.rpos, memory_order_relaxed);
    size_t pos = (size_t)(r % A.cap_frames);
    size_t first = A.cap_frames - pos;
    if (first > (size_t)frames)
        first = frames;
    memcpy(dst, A.ring + pos * A.stride, first * A.stride * sizeof(float));
    if ((size_t)frames > first)
        memcpy(dst + first * A.stride, A.ring, (frames - first) * A.stride * sizeof(float));
    atomic_store_explicit(&A.rpos, r + frames, memory_order_release);
}

static inline void ring_skip(uint64_t frames)
{
    atomic_fetch_add_explicit(&A.rpos, frames, memory_order_release);
}

/* Crossfade `k` frames: dst[i] = a[i]*(1-w) + b[i]*w, w ramping 0..1. */
static inline void xfade(float *dst, const float *a, const float *b, int k, int stride)
{
    for (int i = 0; i < k; i++) {
        float w = (float)(i + 1) / (float)(k + 1);
        for (int c = 0; c < stride; c++)
            dst[i * stride + c] = a[i * stride + c] * (1.f - w) + b[i * stride + c] * w;
    }
}

static void render(float *dst, int n)
{
    const int stride = A.stride;
    const int rate = A.cfg.rate;
    /* The margin is the smallest amount of audio that must still be buffered
     * when a cycle starts; it must at least cover one cycle plus a little. */
    int target = atomic_load(&A.target_frames);
    if (target < n + rate / 1000)
        target = n + rate / 1000;
    atomic_store(&A.eff_target, target);

    if (atomic_exchange(&A.flush_req, 0)) {
        uint64_t w = atomic_load_explicit(&A.wpos, memory_order_acquire);
        atomic_store_explicit(&A.rpos, w, memory_order_release);
        if (atomic_load(&A.state) == AUDIO_PLAYING)
            atomic_store(&A.state, AUDIO_PREBUFFER);
    }

    int state = atomic_load(&A.state);
    int64_t fill = (int64_t)ring_fill();

    if (state == AUDIO_PREBUFFER || state == AUDIO_CONNECTING) {
        /* Start with extra headroom for source burstiness; the controller
         * trims it down to the margin once it has measured the real jitter. */
        int64_t start_fill = target + (int64_t)(PREBUFFER_EXTRA_MS * 1e-3 * rate);
        if (fill < start_fill) {
            memset(dst, 0, (size_t)n * stride * sizeof(float));
            return;
        }
        /* Nothing has been played yet, so jump straight there instead of
         * converging with many small corrections. */
        if (fill > start_fill) {
            ring_skip((uint64_t)(fill - start_fill));
            fill = start_fill;
        }
        A.ema_fill = (double)fill;
        A.win_min = A.prev_min = fill;
        A.win_frames = 0;
        A.underrun_run = 0;
        atomic_store(&A.state, AUDIO_PLAYING);
        state = AUDIO_PLAYING;
    }

    /* Sliding-window minimum of the fill level (two buckets), long enough
     * that a network hole every few seconds keeps its cushion. */
    if (fill < A.win_min) A.win_min = fill;
    A.win_frames += n;
    if (A.win_frames >= (int64_t)(MIN_WINDOW_SECONDS * rate)) {
        A.prev_min = A.win_min;
        A.win_min = fill;
        A.win_frames = 0;
    }
    double alpha = (double)n / (EMA_SECONDS * rate);
    if (alpha > 1.0) alpha = 1.0;
    A.ema_fill += alpha * ((double)fill - A.ema_fill);
    atomic_store(&A.min_fill, (int)(A.prev_min < A.win_min ? A.prev_min : A.win_min));
    atomic_store(&A.avg_fill, (int)A.ema_fill);

    if (fill < n) {
        /* Underrun: play what we have, pad with silence. The padding shifts
         * the stream's timing like an insert, but unlike an insert it was
         * forced, so it is not credited to the minimum: the tracker keeps
         * saying the buffer hit bottom, and the controller inserts until the
         * cushion would have covered this hole. */
        int have = (int)fill;
        if (have > 0)
            ring_read(dst, have);
        memset(dst + have * stride, 0, (size_t)(n - have) * stride * sizeof(float));
        atomic_fetch_add(&A.underruns, 1);
        A.underrun_run += n - have;
        if (A.underrun_run > (int64_t)(UNDERRUN_REBUFFER * rate))
            atomic_store(&A.state, AUDIO_PREBUFFER);
        return;
    }
    A.underrun_run = 0;

    int64_t mfill = A.prev_min < A.win_min ? A.prev_min : A.win_min;

    /* Hard resync when the buffer ran away (network stall burst, suspend...). */
    if (fill > mfill + target + (int64_t)(HARD_LIMIT_MS * 1e-3 * rate)) {
        int64_t keep = target + (int64_t)(PREBUFFER_EXTRA_MS * 1e-3 * rate);
        ring_skip((uint64_t)(fill - keep));
        fill = keep;
        A.ema_fill = (double)fill;
        A.win_min = A.prev_min = fill;
        A.win_frames = 0;
        atomic_fetch_add(&A.resyncs, 1);
        mfill = fill;
    }

    int k = XFADE_MAX;
    if (k > n / 4) k = n / 4;
    int tol = 2 * k;
    if (tol < rate / 250) tol = rate / 250;       /* deadband of >= 4 ms around the margin */

    /* Corrections are rate-limited so trimming a cushion left by a network
     * hole is a 0.5 % tempo change over seconds, not a 20 % one over 300 ms. */
    A.since_adjust += n;
    int may_adjust = A.since_adjust >= ADJUST_GAP_FRAMES;

    if (may_adjust && k >= 4 && mfill > target + tol && fill >= n + k) {
        /* Buffer runs long: read n+k, crossfade the tail into the skipped part. */
        A.since_adjust = 0;
        ring_read(A.scratch, n + k);
        memcpy(dst, A.scratch, (size_t)(n - k) * stride * sizeof(float));
        xfade(dst + (n - k) * stride, A.scratch + (n - k) * stride, A.scratch + n * stride, k, stride);
        A.win_min -= k;
        A.prev_min -= k;
        A.ema_fill -= k;
        atomic_fetch_add(&A.drops, 1);
    } else if (may_adjust && k >= 4 && mfill < target - tol && fill >= n - k && n - k >= 2 * k) {
        /* Buffer runs short: read n-k, repeat the last k with a crossfade. */
        A.since_adjust = 0;
        int l = n - k;
        ring_read(A.scratch, l);
        memcpy(dst, A.scratch, (size_t)(l - k) * stride * sizeof(float));
        xfade(dst + (l - k) * stride, A.scratch + (l - k) * stride, A.scratch + (l - 2 * k) * stride, k, stride);
        memcpy(dst + l * stride, A.scratch + (l - k) * stride, (size_t)k * stride * sizeof(float));
        A.win_min += k;
        A.prev_min += k;
        A.ema_fill += k;
        atomic_fetch_add(&A.inserts, 1);
    } else {
        ring_read(dst, n);
    }
}

static void on_process(void *data)
{
    (void)data;
    struct pw_buffer *b = pw_stream_dequeue_buffer(A.stream);
    if (!b)
        return;
    struct spa_buffer *buf = b->buffer;
    float *dst = buf->datas[0].data;
    if (!dst) {
        pw_stream_queue_buffer(A.stream, b);
        return;
    }
    int stride = A.stride * (int)sizeof(float);
    uint32_t max_frames = buf->datas[0].maxsize / stride;
    uint32_t n = b->requested ? (uint32_t)b->requested : max_frames;
    if (n > max_frames) n = max_frames;
    if (n > A.scratch_frames - XFADE_MAX) n = (uint32_t)(A.scratch_frames - XFADE_MAX);
    atomic_store(&A.quantum, (int)n);

    render(dst, (int)n);

    buf->datas[0].chunk->offset = 0;
    buf->datas[0].chunk->stride = stride;
    buf->datas[0].chunk->size = n * stride;
    pw_stream_queue_buffer(A.stream, b);
}

static void on_state_changed(void *data, enum pw_stream_state old, enum pw_stream_state st, const char *error)
{
    (void)data; (void)old;
    switch (st) {
    case PW_STREAM_STATE_ERROR:
        fprintf(stderr, "pipewire stream error: %s\n", error ? error : "?");
        atomic_store(&A.state, AUDIO_ERROR);
        break;
    case PW_STREAM_STATE_STREAMING:
        if (atomic_load(&A.state) == AUDIO_CONNECTING)
            atomic_store(&A.state, AUDIO_PREBUFFER);
        apply_volume_locked();
        break;
    case PW_STREAM_STATE_PAUSED:
    case PW_STREAM_STATE_CONNECTING:
        if (atomic_load(&A.state) == AUDIO_PLAYING)
            atomic_store(&A.state, AUDIO_PREBUFFER);
        break;
    default:
        break;
    }
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .state_changed = on_state_changed,
    .process = on_process,
};

/* ---- lifecycle ------------------------------------------------------------ */

int audio_init(const struct audio_options *opts)
{
    memset(&A, 0, sizeof(A));
    A.opts = *opts;
    if (A.opts.quantum <= 0)
        A.opts.quantum = 256;
    A.target_ms = 10.0;
    A.phone_volume = -1;
    pw_init(NULL, NULL);
    A.loop = pw_thread_loop_new("hfs-audio", NULL);
    if (!A.loop)
        return -1;
    if (pw_thread_loop_start(A.loop) < 0)
        return -1;
    return 0;
}

static void destroy_stream_locked(void)
{
    if (A.stream) {
        spa_hook_remove(&A.listener);
        pw_stream_destroy(A.stream);
        A.stream = NULL;
    }
    atomic_store(&A.state, AUDIO_IDLE);
}

void audio_close(void)
{
    pw_thread_loop_lock(A.loop);
    destroy_stream_locked();
    pw_thread_loop_unlock(A.loop);
}

void audio_shutdown(void)
{
    if (A.loop) {
        audio_close();
        pw_thread_loop_stop(A.loop);
        pw_thread_loop_destroy(A.loop);
        A.loop = NULL;
    }
    free(A.ring);    A.ring = NULL;
    free(A.scratch); A.scratch = NULL;
    pw_deinit();
}

int audio_configure(const struct audio_config *cfg)
{
    pw_thread_loop_lock(A.loop);
    destroy_stream_locked();   /* after this no process() callback runs */

    A.cfg = *cfg;
    A.stride = cfg->channels;
    A.cap_frames = (size_t)cfg->rate * RING_SECONDS;
    free(A.ring);
    A.ring = calloc(A.cap_frames * A.stride, sizeof(float));
    A.scratch_frames = 8192 + XFADE_MAX;
    free(A.scratch);
    A.scratch = calloc(A.scratch_frames * A.stride, sizeof(float));
    atomic_store(&A.wpos, 0);
    atomic_store(&A.rpos, 0);
    atomic_store(&A.target_frames, (int)(A.target_ms * 1e-3 * cfg->rate));
    atomic_store(&A.graph_rate, 0);
    atomic_store(&A.underruns, 0); atomic_store(&A.overflows, 0);
    atomic_store(&A.drops, 0);     atomic_store(&A.inserts, 0);
    atomic_store(&A.resyncs, 0);
    A.since_adjust = 0;
    A.ema_fill = 0;
    A.underrun_run = 0;

    struct pw_properties *props = pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Audio",
        PW_KEY_MEDIA_CATEGORY, "Playback",
        PW_KEY_MEDIA_ROLE, "Music",
        PW_KEY_APP_NAME, "HiFi Stream Receiver",
        PW_KEY_NODE_NAME, "hifistream-receiver",
        PW_KEY_NODE_DESCRIPTION, "HiFi Stream (Wi-Fi from phone)",
        NULL);
    pw_properties_setf(props, PW_KEY_NODE_LATENCY, "%d/%d", A.opts.quantum, cfg->rate);
    pw_properties_setf(props, PW_KEY_NODE_RATE, "1/%d", cfg->rate);
    pw_properties_set(props, PW_KEY_NODE_LOCK_RATE, "true");
    if (A.opts.force_rate)
        pw_properties_set(props, PW_KEY_NODE_FORCE_RATE, "true");
    if (A.opts.target)
        pw_properties_set(props, PW_KEY_TARGET_OBJECT, A.opts.target);

    A.stream = pw_stream_new_simple(pw_thread_loop_get_loop(A.loop), "HiFi Stream",
                                    props, &stream_events, NULL);
    if (!A.stream) {
        pw_thread_loop_unlock(A.loop);
        return -1;
    }

    uint8_t buffer[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    const struct spa_pod *params[1];
    params[0] = spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat,
        &SPA_AUDIO_INFO_RAW_INIT(.format = SPA_AUDIO_FORMAT_F32,
                                 .channels = (uint32_t)cfg->channels,
                                 .rate = (uint32_t)cfg->rate));

    atomic_store(&A.state, AUDIO_CONNECTING);
    int res = pw_stream_connect(A.stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
                                PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS |
                                PW_STREAM_FLAG_RT_PROCESS,
                                params, 1);
    if (res < 0) {
        fprintf(stderr, "pw_stream_connect: %s\n", spa_strerror(res));
        destroy_stream_locked();
        atomic_store(&A.state, AUDIO_ERROR);
        pw_thread_loop_unlock(A.loop);
        return -1;
    }
    pw_thread_loop_unlock(A.loop);
    return 0;
}

static void apply_volume_locked(void)
{
    if (!A.stream || A.phone_volume < 0)
        return;
    /* Cubic mapping: 50 % on the phone's slider = -18 dB, like PulseAudio/PipeWire UIs. */
    float gain = (float)(A.phone_volume * A.phone_volume * A.phone_volume);
    float vols[2] = { gain, gain };
    pw_stream_set_control(A.stream, SPA_PROP_channelVolumes, (uint32_t)A.cfg.channels, vols, 0);
}

void audio_set_phone_volume(double frac)
{
    if (!A.loop)
        return;
    pw_thread_loop_lock(A.loop);
    A.phone_volume = frac;
    apply_volume_locked();
    pw_thread_loop_unlock(A.loop);
}

void audio_set_target_ms(double ms)
{
    if (ms < 2) ms = 2;
    if (ms > 500) ms = 500;
    A.target_ms = ms;
    if (A.cfg.rate > 0)
        atomic_store(&A.target_frames, (int)(ms * 1e-3 * A.cfg.rate));
}

double audio_get_target_ms(void)
{
    return A.target_ms;
}

void audio_get_stats(struct audio_stats *s)
{
    memset(s, 0, sizeof(*s));
    s->state = atomic_load(&A.state);
    s->cfg = A.cfg;
    s->fill_frames = (int)ring_fill();
    s->target_frames = atomic_load(&A.eff_target);
    s->min_fill_frames = atomic_load(&A.min_fill);
    s->avg_fill_frames = atomic_load(&A.avg_fill);
    s->quantum = atomic_load(&A.quantum);
    s->underruns = atomic_load(&A.underruns);
    s->overflows = atomic_load(&A.overflows);
    s->drops = atomic_load(&A.drops);
    s->inserts = atomic_load(&A.inserts);
    s->resyncs = atomic_load(&A.resyncs);
    s->phone_volume = A.phone_volume;
    for (int c = 0; c < 2; c++) {
        uint32_t bits = atomic_exchange(&A.peak_bits[c], 0);
        memcpy(&s->peak[c], &bits, 4);
    }
    if (A.stream && s->state != AUDIO_IDLE && s->state != AUDIO_ERROR) {
        struct pw_time t;
        if (pw_stream_get_time_n(A.stream, &t, sizeof(t)) == 0 && t.rate.denom > 0) {
            s->graph_rate = (int)(t.rate.denom / (t.rate.num ? t.rate.num : 1));
            s->device_delay_ms = (double)t.delay * t.rate.num / t.rate.denom * 1000.0;
        }
    }
}
