#!/usr/bin/env python3
"""Test sender for the HiFi Stream receiver: streams a synthetic signal using
the HFS1 protocol so the receiver can be tested without a phone.

Examples:
  test_sender.py 127.0.0.1                      # 48 kHz 24-bit 1 kHz sine, 10 s
  test_sender.py 127.0.0.1 --rate 96000 --format f32 --seconds 30
  test_sender.py 127.0.0.1 --drift 200 --loss 0.5   # 200 ppm clock drift, 0.5 % loss
"""
import argparse, socket, struct, time, math, random, sys

FMT = {"s16": 1, "s24": 2, "f32": 3}
BPS = {1: 2, 2: 3, 3: 4}

ap = argparse.ArgumentParser()
ap.add_argument("host")
ap.add_argument("--port", type=int, default=47100)
ap.add_argument("--rate", type=int, default=48000)
ap.add_argument("--format", choices=FMT, default="s24")
ap.add_argument("--channels", type=int, default=2)
ap.add_argument("--freq", type=float, default=1000.0)
ap.add_argument("--amp", type=float, default=0.5)
ap.add_argument("--seconds", type=float, default=10)
ap.add_argument("--drift", type=float, default=0.0, help="sender clock error in ppm (+ = sends too fast)")
ap.add_argument("--loss", type=float, default=0.0, help="percent of packets to drop")
ap.add_argument("--jitter", type=float, default=0.0, help="max random extra delay per packet in ms")
ap.add_argument("--discover", action="store_true", help="broadcast discovery and print replies")
ap.add_argument("--bye", action="store_true", help="send HFS_BYE at the end")
a = ap.parse_args()

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xB8)

if a.discover:
    sock.settimeout(1.0)
    sock.sendto(b"HFS_DISCOVER", ("255.255.255.255", a.port))
    sock.sendto(b"HFS_DISCOVER", (a.host, a.port))
    t0 = time.time()
    while time.time() - t0 < 1.0:
        try:
            data, addr = sock.recvfrom(256)
            print("reply from", addr, data.decode(errors="replace"))
        except socket.timeout:
            break
    sys.exit(0)

fmt = FMT[a.format]
bps = BPS[fmt]
frames = min(a.rate // 200, 1440 // (a.channels * bps))
pkt_dur = frames / a.rate
print(f"{a.rate} Hz {a.format} {a.channels} ch, {frames} frames/packet ({pkt_dur*1000:.2f} ms), "
      f"{a.rate*a.channels*bps*8/1000:.0f} kbit/s -> {a.host}:{a.port}")

seq = 0
n = 0
t_start = time.monotonic()
send_interval = pkt_dur / (1.0 + a.drift * 1e-6)
next_send = t_start
total = int(a.seconds / pkt_dur)
while n < total * frames:
    samples = []
    for i in range(frames):
        v = a.amp * math.sin(2 * math.pi * a.freq * (n + i) / a.rate)
        for c in range(a.channels):
            samples.append(v if c == 0 else -v)
    if fmt == 1:
        payload = struct.pack(f"<{len(samples)}h", *[max(-32768, min(32767, int(round(s * 32768)))) for s in samples])
    elif fmt == 2:
        payload = b"".join(max(-8388608, min(8388607, int(round(s * 8388608)))).to_bytes(3, "little", signed=True) for s in samples)
    else:
        payload = struct.pack(f"<{len(samples)}f", *samples)
    ts_us = int(time.monotonic() * 1e6)
    hdr = struct.pack("<IHBBIIQ", 0x31534648, seq & 0xFFFF, fmt, a.channels, a.rate, frames, ts_us)
    if random.random() * 100 >= a.loss:
        if a.jitter > 0:
            time.sleep(random.random() * a.jitter / 1000)
        sock.sendto(hdr + payload, (a.host, a.port))
    seq += 1
    n += frames
    next_send += send_interval
    dt = next_send - time.monotonic()
    if dt > 0:
        time.sleep(dt)
if a.bye:
    sock.sendto(b"HFS_BYE", (a.host, a.port))
print("done, sent", seq, "packets")
