# HiFi Stream wire protocol (HFS1)

Transport: UDP, default port **47100**. All integers little-endian.

## Audio packet

```
offset size  field
0      4     magic     0x31534648  ("HFS1" as ASCII bytes)
4      2     seq       packet sequence number, wraps at 65536
6      1     format    1 = S16LE, 2 = S24LE (3 bytes, packed), 3 = F32LE
7      1     channels  1 or 2 (interleaved)
8      4     rate      sample rate in Hz (44100 / 48000 / 96000 ...)
12     4     frames    number of frames in this packet
16     8     ts_us     sender CLOCK_MONOTONIC time in microseconds at which the first
                 frame was captured (audio clock, not the send time)
24     ...   payload   frames * channels * bytes_per_sample, interleaved PCM
```

Packets are kept below 1472 bytes (Ethernet MTU minus IP/UDP headers) so they
are never fragmented on Wi-Fi. The sender picks `frames` so one packet is about
5 ms of audio (240 frames at 48 kHz for 24-bit stereo). The sender paces packets
out in small groups (four packets, one Wi-Fi TXOP) rather than in one burst per
capture period, so the receiver's inter-arrival jitter reflects the network,
not the phone's HAL, while the driver can still aggregate frames.

The receiver detects a new session whenever `format`, `channels` or `rate`
changes and rebuilds its output stream. Lost packets are detected from `seq`
gaps and replaced by silence so timing is preserved — and asked for again, see
Retransmission.

## Retransmission

```
offset size  field
0      8     "HFS_NACK"
8      2*n   seq       little-endian sequence numbers the receiver wants again (n <= 64)
```

Sent by the receiver to the address the audio comes from, the moment a gap in
`seq` is noticed (i.e. when the packet after the gap arrives), and again every
10 ms up to four times while the gap is still ahead of playback. The sender
keeps its last 256 packets and answers by resending them unchanged (same `seq`,
same `ts_us`). The receiver copies a resend into the jitter buffer in place of
the silence it had substituted, provided that spot has not been played yet;
otherwise it is counted as late. Nothing ever waits for a resend — unlike TCP,
a packet that cannot be recovered in time simply stays silent — so the added
latency is zero; the recovery window is the jitter buffer's fill (the safety
margin plus whatever the network jitter made it grow to).

## Discovery

The phone broadcasts the ASCII text `HFS_DISCOVER` to `255.255.255.255:47100`
(and to the subnet's directed broadcast). Every receiver answers directly to
the sender's address with `HFS_HERE <hostname>`.

## Control

`HFS_BYE` sent by the phone when streaming stops; the receiver flushes its
buffer and shows "waiting for sender". Not required — the receiver also times
out after 2 s without packets.
