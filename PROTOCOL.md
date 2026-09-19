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
16     8     ts_us     sender CLOCK_MONOTONIC time in microseconds of the first frame
24     ...   payload   frames * channels * bytes_per_sample, interleaved PCM
```

Packets are kept below 1472 bytes (Ethernet MTU minus IP/UDP headers) so they
are never fragmented on Wi-Fi. The sender picks `frames` so one packet is about
5 ms of audio (240 frames at 48 kHz for 24-bit stereo).

The receiver detects a new session whenever `format`, `channels` or `rate`
changes and rebuilds its output stream. Lost packets are detected from `seq`
gaps and replaced by silence so timing is preserved.

## Discovery

The phone broadcasts the ASCII text `HFS_DISCOVER` to `255.255.255.255:47100`
(and to the subnet's directed broadcast). Every receiver answers directly to
the sender's address with `HFS_HERE <hostname>`.

## Control

`HFS_BYE` sent by the phone when streaming stops; the receiver flushes its
buffer and shows "waiting for sender". Not required — the receiver also times
out after 2 s without packets.
