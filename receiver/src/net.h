/* UDP receiver for HFS1 packets, discovery responder and statistics. */
#ifndef HFS_NET_H
#define HFS_NET_H

#include <stdint.h>
#include <stdbool.h>
#include "audio.h"

struct net_stats {
    bool     listening;
    int      port;
    bool     active;             /* packets received within the last 2 s */
    char     sender[64];         /* "ip:port" of the current sender */
    struct audio_config cfg;     /* current session format */
    int      frames_per_packet;
    uint64_t packets, bytes, lost, late, invalid;
    double   bitrate_kbps;       /* averaged over the last second */
    double   jitter_ms;          /* RFC 3550 style inter-arrival jitter */
    double   session_seconds;
    char     dump_path[200];     /* non-empty while dumping to a WAV file */
    uint64_t dump_frames;
};

int  net_start(int port);
void net_stop(void);
bool net_running(void);
/* Starts/stops writing the received stream to a 32-bit float WAV file. */
int  net_set_dump(const char *path);
void net_get_stats(struct net_stats *out);

#endif
