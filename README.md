# PhonePad

Use an Android phone as the **mouse, keyboard, media remote, webcam and microphone** of an old PC
that has none of them.

## 1. Mouse / keyboard (Bluetooth, no extra hardware)
Needs Android 9+ on the phone and Bluetooth on the PC (built in or a USB dongle).
Tap *Visible*, pair the phone from the PC's Bluetooth settings, then tap *Connect*.

Gestures: tap = click, two-finger tap = right click, double-tap-and-drag = drag, two-finger swipe = scroll.
Keys: full US layout, Ctrl/Shift/Alt/Win (tap, then press a key), F1-F12, arrows (hold = repeat), media keys.

## 2. Camera + microphone for the old PC
Tap **Stream**, choose camera/mic, **Start streaming**. The phone serves:

| URL | Content |
|---|---|
| `http://PHONE_IP:8080/` | page with live video + audio |
| `/video` | MJPEG video |
| `/snapshot.jpg` | single frame |
| `/audio` | WAV stream (16 kHz mono) |
| `/audio.pcm` | raw s16le |

The PC must reach the phone: **USB tethering** (best for a PC with no Wi-Fi), phone hotspot, or the same router.


To make apps (Zoom, Skype, Discord) see a *real* webcam/mic on Windows:
- Video: OBS Studio -> add *Media/Browser source* with `/video` -> *Start Virtual Camera*.
- Audio: install VB-Cable, then `ffplay`/VLC the `/audio` URL and set VB-Cable as output; pick *CABLE Output* as the mic.

## Build
`./gradlew assembleDebug` (JDK 17) or push to GitHub: the workflow uploads the APK.
