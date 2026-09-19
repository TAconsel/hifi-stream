# HiFi Stream

Streams everything an Android phone plays to a Linux PC over Wi-Fi as raw,
lossless PCM with ~30 ms of added latency. No root needed on the phone: the
app uses Android's `AudioPlaybackCapture` API, the same mechanism
RootlessJamesDSP uses to grab other apps' audio.

```
 phone                                              PC
 ┌──────────────┐  MediaProjection   ┌────────────┐  UDP 47100  ┌──────────────────┐  PipeWire
 │ Music / games│ ────────────────▶  │ HiFi Stream│ ──────────▶ │ hifistream-      │ ────────▶ DAC
 │ (any app)    │  playback capture  │ (Kotlin)   │  raw PCM    │ receiver (C/GTK4)│  bit-exact
 └──────────────┘                    └────────────┘             └──────────────────┘
```

* **android/** – the sender app (Kotlin, Jetpack Compose, minSdk 29).
* **receiver/** – the receiver (C, GTK4 + PipeWire).
* **PROTOCOL.md** – the 24-byte-header UDP wire format.
* **tools/** – a Python test sender and a Wi-Fi tuning script.

## Receiver (PC)

Requirements: `libgtk-4-dev`, `libpipewire-0.3-dev`, gcc, make.

```sh
make -C receiver
./receiver/hifistream-receiver            # GTK window
./receiver/hifistream-receiver --headless # terminal only, prints stats once a second
sudo make -C receiver install             # optional: /usr/local/bin + desktop entry
```

Options: `--port N` (default 47100), `--buffer MS` (safety margin, default 10),
`--quantum FRAMES` (PipeWire cycle, default 256), `--target NODE` (a specific
sink), `--force-rate` (switch the PipeWire graph rate even if other apps are
playing), `--dump FILE` (also record the received stream to a float WAV).

The window shows the sender, format, packet loss, jitter, buffer fill,
corrections, PipeWire state (including whether the output is bit-exact or
being resampled), an estimated latency and a peak meter. "Record to WAV…"
writes exactly what arrives, which is how the numbers below were verified.

### How the receiver keeps latency low without dropouts

* Raw PCM over UDP, ~5 ms per packet, no codec – nothing to decode.
* PipeWire stream with a 256-frame quantum (5.3 ms at 48 kHz) that asks the
  graph to run at the stream's rate, so 44.1/48/96 kHz all play bit-exact.
* Jitter buffer regulated on the **minimum** fill over the last second: the
  *safety margin* slider says how much audio must always be buffered; the
  actual latency adapts to the measured jitter on top of that. Android's
  capture delivers audio in 20 ms bursts, so the buffer settles at ≈ 25 ms
  average with a 10 ms margin.
* Clock drift between phone and DAC is absorbed with 64-frame crossfaded
  drops/inserts (WSOLA-style, no pitch change, no clicks); when the buffer is
  in tolerance the samples pass through untouched.
* Lost packets become silence of the right length so timing is preserved;
  a stall longer than ~100 ms triggers a hard resync so latency never creeps up.

## Android app

Build with Android Studio, or from the command line (needs a JDK 17+ and the
Android SDK; `android/local.properties` points at both):

```sh
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Usage: open the app, tap **Discover receivers** (or type the PC's IP), choose
the sample rate and bit depth, tap **Start streaming** and accept Android's
"Share your screen" prompt (that prompt is how playback capture is authorised;
nothing visual is recorded). A persistent notification with a Stop button
appears while streaming. **Mute phone while streaming** sets the media volume
to 0 so you only hear the PC – capture is independent of the phone's volume.
**Play test tone** sends a 1 kHz tone through the media path to check the link.

Implementation notes (`CaptureService.kt`): foreground service of type
`mediaProjection`, `AudioRecord` with `AudioPlaybackCaptureConfiguration`
matching `USAGE_MEDIA/GAME/UNKNOWN`, float capture converted to the wire
format, `THREAD_PRIORITY_URGENT_AUDIO`, `WIFI_MODE_FULL_LOW_LATENCY` lock,
DSCP EF on the socket (Wi-Fi WMM voice queue), partial wake lock.

## Measured on a Sony XQ-FS72 (Android 16) → Ubuntu 26.04 laptop

* Transport verified bit-exact: every non-lost 24-bit / float sample in the
  receiver's WAV dump equals the source sample.
* Steady state at 48 kHz / 24-bit: 0.02 % packet loss, 0.6 ms network jitter,
  ~24 ms buffered + 5.3 ms PipeWire ≈ 29 ms on the PC; phone capture adds ~20 ms.
* 96 kHz / 24-bit (4.7 Mbit/s) streams just as well; PipeWire switched the
  graph to 96 kHz.

### Honest limits of "high resolution"

Android's playback capture runs through the *remote submix* audio module. On
this phone (and on AOSP in general) that module is **16-bit, 48 kHz max**:
even a 32-bit-float source arrives 16-bit quantised, and 96 kHz capture is
Android upsampling its 48 kHz mixer. The app detects this from the data and
shows "Captured audio is 16-bit" in the status card. The transport, the
receiver and the WAV dump are all 24-bit/float clean, so a phone whose submix
supports more will pass it through unchanged. Apps that opt out of capture
(some DRM streaming apps) are silent in the stream; calls and notifications
are never captured.

## If you hear dropouts

1. Check the receiver's **Network** row. Bursts of 100–300 ms loss with
   otherwise clean packets are almost always the PC's Wi-Fi:
   * 802.11 power save, and
   * NetworkManager's background scan (`bgscan=simple:30:-70:86400`): a full
     channel scan every 30 s whenever the signal is below −70 dBm, ~1 s of
     stall each time.

   `sudo tools/wifi-lowlatency.sh` turns both off until the next reconnect;
   on the test laptop this took the network delay variation from p99 ≈ 100 ms
   down to 9 ms. Moving closer to the AP has the same effect on bgscan.
2. Raise the safety-margin slider (10 → 20–30 ms).
3. Keep the phone app in the foreground / screen on if the phone's own Wi-Fi
   scanning causes gaps; the low-latency Wi-Fi lock only applies then.
4. Expect a few seconds of disturbance when another app *starts* playing –
   Android reconfigures its audio path and the capture stalls briefly; the
   receiver resyncs automatically.

## Testing without a phone

```sh
./receiver/hifistream-receiver --headless --dump rx.wav &
tools/test_sender.py 127.0.0.1 --rate 96000 --format f32 --drift 300 --loss 0.5 --seconds 20 --bye
tools/test_sender.py 192.168.20.183 --discover   # exercise discovery
```
