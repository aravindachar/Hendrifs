# Hendrifs
by Rizzvind

Control your Blackstar ID:Core V4 straight from an Android tablet or phone, no PC needed.
Plug in with a USB-C cable and get the amp, effects, patches, tuner and CabRig on a vintage gold amp-head screen.

> Not affiliated with or endorsed by Blackstar Amplification. "Blackstar" and "ID:Core" are their trademarks.

## What it does
- **Amp:** voice, gain, volume, bass, middle, treble, ISF, resonance, presence.
- **Pedalboard:** modulation, delay and reverb with types and levels, the amp's own on / focus / off switching, tap tempo, noise gate.
- **Patches:** the amp's 6 patches, store to the amp (behind a write-protect switch, read back and checked), and a library on the device with backup and restore of all 6.
- **Tuner:** needle meter with flat / in tune / sharp lamps.
- **CabRig and settings:** cabinet, mic and routing options, footswitch modes.
- **Stage:** big footswitch-style buttons for playing live.
- **Demo amp:** try every screen without the amp.
- **USB log:** Settings → Service shows and shares every packet, for debugging.

Status: the knob, effect and patch commands follow the protocol of earlier ID amps and still need confirming on a V4.
Items marked **Beta** in the app (gate, CabRig, routing, footswitch) are the least certain.

## Requirements
- Android 8.0 or newer, with USB host (OTG) support.
- A USB-C data cable from the amp to the device. Some phones (OnePlus, Oppo, Realme) need OTG switched on in Settings.

## Build
```
cd app
./gradlew testDebugUnitTest assembleRelease
```
Needs JDK 17+ and the Android SDK (platform 36). The APK lands in Gradle's build folder under `outputs/apk/release/`.

## How it is put together
- `app/app/src/main/assets/www/index.html`: the whole interface, shown full screen in a WebView. Open it in a desktop browser with `#demo` to preview without a device.
- `MainActivity.kt`: hosts the page and bridges it to the amp (`HendrifsNative.send(json)` in, `PT.onState(...)` out).
- `AmpController.kt`: one worker thread for all USB reads and writes.
- `protocol/IdCoreProtocol.kt`: the 64-byte HID packet format, with unit tests.
- `usb/UsbHidTransport.kt`: claims only the amp's HID interface, so its USB audio keeps working.
- `research/`: notes on the protocol and on the V4 firmware.
- The `*.html` files in the root are design explorations.

## Fonts
Oswald, Playfair Display SC, Yellowtail, Fraunces and Damion (SIL Open Font License) and DSEG14 (SIL OFL), bundled so the app works offline.
