/* PipeWire output with a jitter buffer and click-free drift compensation. */
#ifndef HFS_AUDIO_H
#define HFS_AUDIO_H

#include <stdint.h>
#include <stdbool.h>

struct audio_config {
    int rate;
    int channels;
    int format;      /* enum hfs_format, only used for display */
};

enum audio_state {
    AUDIO_IDLE = 0,       /* no stream configured */
    AUDIO_CONNECTING,     /* stream created, waiting for PipeWire */
    AUDIO_PREBUFFER,      /* waiting for the buffer to reach the target */
    AUDIO_PLAYING,
    AUDIO_ERROR,
};

struct audio_stats {
    int state;
    struct audio_config cfg;
    int fill_frames;        /* frames currently buffered */
    int min_fill_frames;    /* minimum fill over the last second: the controlled quantity */
    int avg_fill_frames;    /* smoothed average fill: roughly the added latency */
    int target_frames;      /* effective safety margin (after clamping to quantum) */
    int quantum;            /* frames per PipeWire process cycle */
    int graph_rate;         /* PipeWire graph rate; equals cfg.rate when bit-exact */
    double device_delay_ms; /* delay reported by PipeWire between the stream and the DAC */
    double rate_corr;       /* rate correction the fill loop asks for, 1.0 = none; see audio.c */
    bool   remote_rate;     /* true: the correction is sent to the phone instead of resampling here */
    uint64_t underruns;
    uint64_t overflows;
    uint64_t drops;         /* crossfaded frame drops (buffer running long) */
    uint64_t inserts;       /* crossfaded frame inserts (buffer running short) */
    uint64_t resyncs;       /* hard skips after a large deviation */
    float peak[2];          /* peak-hold since last call, linear 0..1+ */
    double phone_volume;    /* as set by audio_set_phone_volume, -1 if none */
};

struct audio_options {
    int quantum;            /* requested PipeWire quantum in frames, 0 = default (256) */
    bool force_rate;        /* set node.force-rate so the graph always follows the stream */
    const char *target;     /* PipeWire target object (node name or id), NULL = default sink */
};

int  audio_init(const struct audio_options *opts);
/* Rate matching on the phone: the fill loop's correction is reported to the
 * sender (HFS_RX) and applied there; this receiver never resamples. Meant
 * for receivers without the CPU for a resampler (microcontrollers); the PC
 * receiver offers it for testing that path. */
void audio_set_remote_rate(bool on);
bool audio_get_remote_rate(void);
void audio_shutdown(void);

/* (Re)creates the output stream. Safe to call from the network thread. */
int  audio_configure(const struct audio_config *cfg);
/* Tears down the output stream (sender went away). */
void audio_close(void);

/* Producer side (network thread). Samples are interleaved float. */
void audio_push(const float *samples, int frames);
void audio_push_silence(int frames);
/* Stream position (frames) the next push will land at. */
uint64_t audio_write_pos(void);
/* Overwrites frames at a stream position that has been pushed but not yet
 * played, e.g. a retransmitted packet replacing its silence. False if too late. */
bool audio_patch(uint64_t at, const float *samples, int frames);
/* Drops everything buffered and re-enters prebuffering. */
void audio_flush(void);

/* Volume forwarded from the phone (0..1 of its volume slider), applied to the
 * PipeWire stream with the same cubic law desktop mixers use; -1 = full scale. */
void   audio_set_phone_volume(double frac);
void   audio_set_target_ms(double ms);
double audio_get_target_ms(void);
void   audio_get_stats(struct audio_stats *out);

#endif
