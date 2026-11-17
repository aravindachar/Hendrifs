# Architect 2.1.1 + ID:Core V4: what can be controlled

Sources: ID:Core V4 owner's manual (`research/manual/manual-english.txt`), the text and images inside
`Blackstar Architect.exe` (`research/architect-strings.txt`, `research/architect-ui/png/`).
Architect cannot show its V4 screens without the amp, so its *layout* is inferred from its images and text.

## Look and feel of Architect
- Charcoal background, thin red accent rule under the top bar, white uppercase labels with wide letter spacing.
- An amp faceplate panel with round black knobs, coloured LEDs (red / amber / green / white), effect buttons.
- Left: patch panel (Amplifier patches, Local patches, Blackstar presets, Setlists) with genre tiles
  (Rock, Metal, Punk, Surf, Pop, Mixture, Other).
- CabRig page: cabinet picture picker, microphone picker, on/off axis, room, EQ faders.
- Settings: Footcontroller, Audio mode, Manual mode settings, Backup / Restore, Firmware.

Core Tab copies this *style* with its own drawn knobs and LEDs. No Blackstar images or logos are used.

## Feature inventory for the ID:Core V4

Status: **Mapped** = packet known (from the ID / ID:Core V2 protocol, still to be confirmed on V4).
**Needs capture** = in the UI but disabled until a V4 USB log shows its packet.

| Area | Control | Range / options | In patch? | Status |
|---|---|---|---|---|
| Amp | Voice | Clean Warm, Clean Bright, Crunch, Super Crunch, OD 1, OD 2 | yes | Mapped (0x01) |
| Amp | Gain | 0–10 | yes | Mapped (0x02) |
| Amp | Volume | 0–10, **global** | no | Mapped (0x03) |
| Amp | ISF | USA ↔ UK | yes | Mapped (0x07) |
| Tone (Architect only) | Bass, Middle, Treble | 0–10 | yes | Mapped (0x04–0x06) |
| Tone (Architect only) | Resonance, Presence | 0–10 | yes | Mapped (0x0B, 0x0C) |
| Effects | MOD / DLY / REV on, off, focus | LED off / red / green | yes | Mapped (0x0F–0x11, focus 0x24) |
| Modulation | Type | Phaser, Chorus/Flanger, Envelope, Tremolo | yes | Mapped (0x12) |
| Modulation | Phaser: Mix / Depth / Speed | | yes | Mapped (0x13 / 0x15 / 0x16) |
| Modulation | Chorus/Flanger: Morph / Mix / Speed | | yes | Mapped |
| Modulation | Envelope: Sensitivity / Depth | | yes | Mapped |
| Modulation | Tremolo: FM depth / AM depth / Speed | | yes | Mapped |
| Delay | Type | Linear, Analogue, Tape, Multi | yes | Mapped (0x17) |
| Delay | Feedback / Level / Time | Time 100–2000 ms | yes | Mapped (0x18 / 0x1A / 0x1B) |
| Reverb | Type | Room, Hall, Spring, Plate | yes | Mapped (0x1D) |
| Reverb | Size / Level | | yes | Mapped (0x1E / 0x20) |
| Effects | Tap tempo | taps → delay time | yes | App-side (sets Delay Time) |
| Noise gate | On/off per voice, Amount, Sensitivity | | manual settings | Needs capture |
| Patches | Select patch 1–6 (one per voice) | | | Mapped (`02 01`) |
| Patches | Read names / data | | | Mapped (`02 04`, `02 05`) |
| Patches | Store to amp, rename | 20 chars | | Mapped (`02 02` + `02 03`), behind a safety switch |
| Patches | Manual / Patch mode | | | Amp reports it (`08 03`); sending: Needs capture |
| Library | Local patches, genres, share, backup all 6, restore | | | App-side |
| Tuner | Note + cents | E A D G B E | | Amp reports it (`08 11`, `09`); start from app: Needs capture |
| Power | Power reduction | Full / 1 W | | Needs capture |
| USB | Audio mode | Stereo emulated, Mono emulated / clean, Reamping | | Needs capture |
| USB | Music playback volume (Tap + Volume) | | | Needs capture |
| Footswitch (20/40) | Mode | Default (toggle 2 patches + FX), Alternative (patch up/down) | | Needs capture |
| Footswitch (20/40) | Patch A / Patch B for toggle | | | Needs capture |
| CabRig Lite | Cabinet | 23 cabinets (list in app) | | Needs capture |
| CabRig Lite | Microphone, axis | Dynamic 57/421, Condenser 67/414, Ribbon 121/160; On/Off axis | | Needs capture |
| CabRig Lite | Room type / level, Low/High cut | Small/Large Room (± damped), Chamber | | Needs capture (may not all exist on Lite) |
| Bluetooth (10 BT) | Pairing | | | Hardware button only |
| Firmware update | | | | Deliberately left to Architect on the PC |
| Factory reset | | | | Hardware only: hold MOD+DLY+REV at power-on |
