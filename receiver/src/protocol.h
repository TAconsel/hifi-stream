/* HiFi Stream wire protocol, see ../../PROTOCOL.md */
#ifndef HFS_PROTOCOL_H
#define HFS_PROTOCOL_H

#include <stdint.h>

#define HFS_MAGIC        0x31534648u   /* "HFS1" */
#define HFS_DEFAULT_PORT 47100
#define HFS_HDR_SIZE     24
#define HFS_MAX_PACKET   1472

#define HFS_DISCOVER_MSG "HFS_DISCOVER"
#define HFS_HERE_MSG     "HFS_HERE"
#define HFS_BYE_MSG      "HFS_BYE"
#define HFS_VOLUME_MSG   "HFS_VOL"   /* "HFS_VOL 0.63": phone media volume, fraction of max */
#define HFS_NACK_MSG     "HFS_NACK"  /* followed by uint16 LE sequence numbers to resend */
#define HFS_NACK_MAX     64          /* sequence numbers per NACK message */

enum hfs_format {
    HFS_FMT_S16LE = 1,
    HFS_FMT_S24LE = 2,   /* 3 bytes per sample, packed */
    HFS_FMT_F32LE = 3,
};

struct hfs_header {
    uint32_t magic;
    uint16_t seq;
    uint8_t  format;
    uint8_t  channels;
    uint32_t rate;
    uint32_t frames;
    uint64_t ts_us;
};

static inline int hfs_bytes_per_sample(int fmt)
{
    switch (fmt) {
    case HFS_FMT_S16LE: return 2;
    case HFS_FMT_S24LE: return 3;
    case HFS_FMT_F32LE: return 4;
    default:            return 0;
    }
}

static inline const char *hfs_format_name(int fmt)
{
    switch (fmt) {
    case HFS_FMT_S16LE: return "16-bit";
    case HFS_FMT_S24LE: return "24-bit";
    case HFS_FMT_F32LE: return "32-bit float";
    default:            return "?";
    }
}

/* Parses a header from a raw packet. Returns 0 on success. */
static inline int hfs_parse_header(const uint8_t *p, unsigned len, struct hfs_header *h)
{
    if (len < HFS_HDR_SIZE)
        return -1;
    h->magic    = (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
    h->seq      = (uint16_t)(p[4] | p[5] << 8);
    h->format   = p[6];
    h->channels = p[7];
    h->rate     = (uint32_t)p[8] | (uint32_t)p[9] << 8 | (uint32_t)p[10] << 16 | (uint32_t)p[11] << 24;
    h->frames   = (uint32_t)p[12] | (uint32_t)p[13] << 8 | (uint32_t)p[14] << 16 | (uint32_t)p[15] << 24;
    h->ts_us    = 0;
    for (int i = 7; i >= 0; i--)
        h->ts_us = h->ts_us << 8 | p[16 + i];
    if (h->magic != HFS_MAGIC)
        return -1;
    int bps = hfs_bytes_per_sample(h->format);
    if (bps == 0 || h->channels < 1 || h->channels > 2 || h->rate < 8000 || h->rate > 384000)
        return -1;
    if (h->frames == 0 || HFS_HDR_SIZE + (unsigned)h->frames * h->channels * bps != len)
        return -1;
    return 0;
}

#endif
