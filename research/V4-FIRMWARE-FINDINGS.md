# What the ID:Core V4 firmware says (from inside Architect 2.1.1)

Architect ships the amp firmware images. One of them is built from
`...\Projects\155-Tone_NXP\Workspace\IDCoreV4-MainApp\...`, i.e. the ID:Core V4 main app.
Its readable text is saved in `idcore-v4-firmware-strings.txt`.

## No lock-out
- No signature or encryption on the USB control path. The only handshake text is
  "Handshake Received" / "BL Handshake Received" (BL = bootloader, used for firmware updates).
- The normal handshake is the legacy ID startup packet `81 00 00 04 03 06 02 7A`, which PowerTube sends.
- "Authentication" text in Architect belongs to its online account login (Google/Apple), not the amp.
- A USB device cannot tell which program is talking to it, so nothing needs disguising.

## Global settings table (wire id = position, counting from SWSEnabled = 1)
Two ids match what the original ID amps already send: `08 03` = manual mode and `08 11` = tuner,
and the startup reply `08 04` = selected patch. That locks the numbering in.

| id | name | used for |
|---|---|---|
| 0x01 | SWSEnabled | Super Wide Stereo |
| 0x03 | ManualMode | Patch / Manual |
| 0x04 | PatchNumber | selected patch |
| 0x05 | SoftwareVersion | firmware version |
| 0x0B / 0x0C | PatchLoad / PatchSave | |
| 0x0E | EffectsLoop | |
| 0x0F | LineInToEmulatedOut | routing |
| 0x10 | USBToEmulated | routing |
| 0x11 | TunerEnable | tuner on/off |
| 0x13–0x17 | EmulatedCabSize, SpeakerType, MicPosition, MicType, MicAngle | CabRig Lite |
| 0x19 | USBToPowerAmp | routing |
| 0x1A | Noisegate | noise gate |
| 0x1B | LineInToPowerAmp | routing |
| 0x1C–0x1E | FootSwitchMode, FootSwitchPatchA, FootSwitchPatchB | FS-11 / FS-18 |
| 0x29 | SpeakerMute | |
| 0x2F | NoiseReductionEnabled | background noise reduction |

Setting a global is assumed to mirror setting a control: `08 <id> 00 01 <value>` (controls are `03 <id> 00 01 <value>`).
**Still to confirm on the amp.**

## Patch parameters
The firmware's patch table lists `ModType, ModAdjust, ModAdj2, ModLevel, ModRate, DlyType, DlyAdjust, DlyAdj2,
DlyLevel, TapDlyLo, TapDlyHi, RvbType, RvbAdjust, RvbAdj2, RvbLevel` in the same order as the legacy control ids
0x12–0x20 (delay time low/high at 0x1B/0x1C). The effects block is the same protocol as the ID:Core V1/V2.

## Not found
Power reduction (1 W) has no setting: it is very likely the front-panel button only.
