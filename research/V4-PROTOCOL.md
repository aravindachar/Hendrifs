# ID:Core V4 USB protocol: confirmed on a real amp

Captured 2026-11-17 from an ID:Core Stereo 10 V4 (USB 27D4:0013, firmware 1.0.4).
Sources: `tools/probe.py` (the amp's own reports while its knobs were turned) and
`tools/spy_architect.py` (every report Blackstar Architect 2.1.1 wrote). Raw logs are in `captures/` (not committed).

All reports are 64 bytes, report id 0. **Only the first `4 + length` bytes are meaningful.** The rest is leftover
buffer memory and changes from packet to packet, so never compare or decode past the length byte.

## The one rule: value blocks
`<kind> <first id> 00 <count> <value for first id> <value for next id> ...`

- `kind 03` = patch / live sound parameters. `kind 08` = amp-wide settings (globals).
- A single knob is a block of 1: `03 02 00 01 40` = gain 64.
- Delay time is the only 2-byte value (`1B` low, `1C` high). Architect sends it as `03 1B 00 02 <lo> <hi>`.
- The amp **echoes every command it receives** to every open handle, including the sender. The app must ignore
  echoes of values older than its latest change.
- While a knob is turned, the amp repeats the same report every ~10 ms. Drop duplicates.

## Amp → app
| Report | Meaning |
|---|---|
| `07 00 00 03 01 00 04` | firmware version 1.0.4 (reply to the startup packet) |
| `03 01 00 2A` + 42 values | full live sound (reply to the startup packet) |
| `03 01 00 29` + 41 values | full live sound after a patch change |
| `03 0F 00 16` / `03 10 00 15` / `03 11 00 14` | MOD / DLY / REV pressed on the amp: block from that switch up to FX focus (`24`) |
| `03 13 00 14` ... | catch-up block sent when the tuner closes (changes made while muted) |
| `03 <id> 00 01 <v>` | one knob changed (the amp's own knob, or an echo) |
| `08 01 00 2A` + 42 values | all globals (reply to the startup packet, and whenever the tuner opens from the amp) |
| `08 11 00 01 00` | tuner closed |
| `02 06 <slot> 3C` + values | patch `slot` selected (the Voice knob selects patches 1–6), followed by `03 01 00 29` |
| `02 04 <slot> 15 <name, NUL-terminated>` | patch name (mixed case, up to 20 characters) |
| `02 05 <slot> 2A` + 42 values | stored patch settings |
| `09 <note> <cents + 50> 00 <5 floats>` | tuner reading, ~10 per second. note 0 = no signal; 1–12 chromatic |
| `B0 00 00 04 ...` | unknown, sent once at startup. Ignore. |

## App → amp
| Command | Bytes | Confirmed by |
|---|---|---|
| Startup | `81 00 00 04 03 06 02 7A` | amp replies with version, sound, globals, patch |
| Set parameter | `03 <id> 00 01 <v>` | Architect + amp echo |
| Set delay time | `03 1B 00 02 <lo> <hi>` (100–1000 ms) | Architect |
| Send a whole sound | `03 01 00 2B` + 43 values (volume byte `00`) | Architect loading a .bstar |
| Select patch | `02 01 <slot> 00` | legacy, amp answers `02 06` |
| Read name / settings | `02 04 <slot> 00`, `02 05 <slot> 00` | Architect |
| Store patch | `02 02 <slot> <name length> <name>` then `02 03 <slot> 2B` + 43 values | Architect, then restored slot 1 by hand and read back |
| Tuner on / off | `08 11 00 01 01` / `08 11 00 01 00` | Architect + probe |
| Other globals | `08 <id> 00 01 <v>` | same pattern as tuner, not yet tried one by one |

## Parameter ids (`03`)
| id | Architect control | range |
|---|---|---|
| 01 | Voice | 0–5 (Clean Warm, Clean Bright, Crunch, Super Crunch, OD 1, OD 2) |
| 02 | Gain | 0–127 |
| 03 | Volume (global knob, `00` inside patches) | 0–127 |
| 04 05 06 | Bass, Middle, Treble | 0–127 |
| 07 | ISF | 0–127 |
| 08, 0E | TVP value / TVP switch (.bstar `<TVP Status="1">4</TVP>`) | |
| 0B 0C | Resonance, Presence | 0–127 |
| 0F 10 11 | Mod / Delay / Reverb on | 0–1 |
| 12 | Mod type | 0 Phaser, 1 Chorus-Flanger, 2 Envelope, 3 Tremolo |
| 13 14 15 16 | Mod Adjust1 (Morph, 0–31), Adjust2 (Depth), Level (Mix), Rate (Speed) | |
| 17 | Delay type | 0 Linear, 1 Analogue, 2 Tape, 3 Multi |
| 18 19 1A 1B-1C | Delay Adjust1 (Feedback, 0–31), Adjust2, Level, Time (ms) | |
| 1D | Reverb type | 0 Room, 1 Hall, 2 Spring, 3 Plate |
| 1E 1F 20 | Reverb Adjust1 (Size, 0–31), Adjust2, Level | |
| 24 | FX focus | 0 none, 1 mod, 2 delay, 3 reverb |
| 26 27 | Noise gate Sensitivity, Amount (per patch) | 0–127 |

## Global ids (`08`), from the firmware table, values seen in `08 01 00 2A`
`11` tuner on, `12` tuner sensitivity, `13–17` CabRig (cab size, speaker, mic position, mic type, mic angle),
`1C–1E` footswitch mode / patch A / patch B. Power reduction, TAP and MANUAL send nothing over USB.

## .bstar files (Architect patches)
XML `<Sound>`. Loading one sends `03 01 00 2B` with: Voice→01, Gain→02, Bass/Middle/Treble/ISF→04–07, TVP→08/0E,
Modulation/Delay/Reverb `Status`→0F/10/11, `<Types>` text→12/17/1D, Adjust1→13/18/1E, Adjust2→14/19/1F, Level→15/1A/20,
Rate→16, Delay Tempo→1B (ms). Volume is not applied.
