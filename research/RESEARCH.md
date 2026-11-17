# Research: controlling a Blackstar ID:Core V4 from a OnePlus Pad (Android)

Date: 2026-11-17

## Verdict
Feasible. Android supports USB host (OTG) and lets an app claim a vendor HID
interface directly, which is exactly how the amp is controlled. Nothing needs
root, a custom driver, or the PC.

## Facts established on this PC
| Fact | Source |
|---|---|
| ID:Core V4 USB ID = **VID 0x27D4 (10196), PID 0x0013 (19)** | Blackstar driver INF, `C:\Program Files\Blackstar Amplification\USB Audio Driver\x64\BlackstarUsbAudio.inf` |
| Same driver also covers HT MK III (0x00CF), Polar 2 (0x00C0), Polar 4 (0x00C1) | same INF |
| Architect 2.1.1 loads `hid.dll` and has a `HIDClass` | loaded modules / strings of the running app |
| Architect draws the V4 with its `IDCoreV3AmpPanel` / `IDCoreV3FXComponent` classes (no V4-specific panel exists) | strings dump, `research/architect-strings.txt` |
| Architect knows V4 sizes: 10W, 10W with Bluetooth, 20W, 40W | strings dump |

So control goes over **USB HID**, and the V4 is treated by Blackstar's own software
as a member of the V3 family.

## Prior work (cloned in `research/refs/`)
| Project | Amp | What it gives us |
|---|---|---|
| [outsider](https://github.com/jonathanunderwood/outsider) | original ID series | First reverse-engineering of the ID protocol (Python, PyUSB) |
| [dms-blackstar](https://github.com/metalfury/dms-blackstar) | ID:Core V2 (PID 0x0011) | Clean protocol notes; confirms V2 uses the same protocol as the original ID amps. GPL-3. |
| [Surveyor](https://github.com/fabiodalez-dev/Surveyor-Blackstar-Amped-3) | AMPED 3 (PID 0x0072) | A working **Android USB-OTG HID app** for a Blackstar device: proves the transport on Android. No license file, so used only as a reference, no code copied. |

### ID-family protocol (from outsider / dms-blackstar, verified there on V1/V2)
- 64-byte HID reports, report id 0, interrupt endpoints.
- Startup / request state: `81 00 00 04 03 06 02 7A`
- Amp reports full state: `03 01 00 2A` + 42 bytes; control id *N* lives at byte *N+3*.
- Set control: `03 <id> 00 01 <value>`; 2-byte values (delay time): `03 <id> 00 02 <lo> <hi>`
- Select patch: `02 01 <slot> 00`; read name `02 04 <slot> 00`; read patch `02 05 <slot> 00`
- Store patch: name packet `02 02 <slot> 15 <ascii...>` then `02 03 <slot> 29` + settings
- Amp events: `02 06 <slot>` patch changed, `08 03 .. .. <m>` manual mode, `08 11` tuner, `09 <note> <cents+50>` tuner reading
- Control ids: voice 01, gain 02, volume 03, bass 04, middle 05, treble 06, ISF 07,
  mod on 0F, delay on 10, reverb on 11, mod type 12, mod segval 13, mod level 15,
  mod speed 16, delay type 17, delay feedback 18, delay level 1A, delay time 1B (2 bytes),
  reverb type 1D, reverb size 1E, reverb level 20, fx focus 24.

## Unknowns (need the amp)
1. Does the V4 (PID 0x0013) answer the same packets? Very likely the same family,
   but V3/V4 have more effect types (Envelope, Octaver, Chorus/Flanger, Multi delay,
   noise gate...), so type ids and maybe extra control ids differ.
2. Patch count on V4 (V2 = 6 slots).
3. CabRig Lite / Bluetooth settings: probably separate opcodes.

## How we close the unknowns
- **On the tablet:** the app's Console tab logs every packet in/out and saves it to a file.
  Plug in, turn each physical knob on the amp one at a time, and the log shows which byte moves.
- **On the PC (deeper):** Wireshark + USBPcap (installed) to record Architect talking to the amp
  while changing one setting at a time. Save captures in `captures/`.

## Tablet notes
- OnePlus Pad has USB-C with OTG. Use a plain USB-C to USB-B cable (or USB-C OTG adapter + the
  amp's normal USB cable). The amp has its own power supply, so the tablet does not power it.
- Android asks "Allow app to access ID:Core?" on first plug-in; tick "always open".
