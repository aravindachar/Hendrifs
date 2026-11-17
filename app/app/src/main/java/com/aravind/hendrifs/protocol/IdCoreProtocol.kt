package com.aravind.hendrifs.protocol

/**
 * Blackstar ID-family USB HID protocol: 64-byte reports, report id 0.
 *
 * Control ids and packet layouts come from the public ID / ID:Core V1-V2 reverse engineering.
 * The ID:Core V4 firmware (embedded in Architect) lists its effect parameters and global settings
 * in the same order as these ids (see research/V4-FIRMWARE-FINDINGS.md). Everything here is still
 * to be confirmed with a USB log from a real V4.
 */
object IdCoreProtocol {
    const val VENDOR_ID = 0x27D4
    const val PACKET = 64

    val MODELS = mapOf(
        0x0001 to "ID:TVP",
        0x0010 to "ID:Core",
        0x0011 to "ID:Core V2",
        0x0013 to "ID:Core V4",
    )

    const val SLOTS = 6
    const val NAME_LEN = 20

    val VOICES = listOf("Clean Warm", "Clean Bright", "Crunch", "Super Crunch", "OD 1", "OD 2")
    val MOD_TYPES = listOf("Phaser", "Chorus/Flanger", "Envelope", "Tremolo")   // V4 manual
    val DELAY_TYPES = listOf("Linear", "Analogue", "Tape", "Multi")
    val REVERB_TYPES = listOf("Room", "Hall", "Spring", "Plate")

    /** Per-patch controls: value of control [id] sits at byte id+3 of a full-state packet. */
    enum class Control(val id: Int, val min: Int, val max: Int, val twoBytes: Boolean = false) {
        VOICE(0x01, 0, 5),
        GAIN(0x02, 0, 127),
        VOLUME(0x03, 0, 127),
        BASS(0x04, 0, 127),
        MIDDLE(0x05, 0, 127),
        TREBLE(0x06, 0, 127),
        ISF(0x07, 0, 127),
        RESONANCE(0x0B, 0, 127),
        PRESENCE(0x0C, 0, 127),
        MOD_ON(0x0F, 0, 1),
        DELAY_ON(0x10, 0, 1),
        REVERB_ON(0x11, 0, 1),
        MOD_TYPE(0x12, 0, 3),
        MOD_SEGVAL(0x13, 0, 31),
        MOD_LEVEL(0x15, 0, 127),
        MOD_SPEED(0x16, 0, 127),
        DELAY_TYPE(0x17, 0, 3),
        DELAY_FEEDBACK(0x18, 0, 31),
        DELAY_LEVEL(0x1A, 0, 127),
        DELAY_TIME(0x1B, 100, 2000, twoBytes = true),
        REVERB_TYPE(0x1D, 0, 3),
        REVERB_SIZE(0x1E, 0, 31),
        REVERB_LEVEL(0x20, 0, 127),
        FX_FOCUS(0x24, 0, 3);          // 0 none, 1 mod, 2 delay, 3 reverb

        val offset get() = id + 3

        companion object {
            fun byName(n: String) = entries.firstOrNull { it.name == n }
        }
    }

    /** Amp-wide settings from the V4 firmware's global table (`08 <id> ...`). */
    object Global {
        const val SWS = 0x01
        const val MANUAL = 0x03
        const val PATCH_NUMBER = 0x04
        const val SOFTWARE_VERSION = 0x05
        const val EFFECTS_LOOP = 0x0E
        const val LINEIN_TO_EMULATED = 0x0F
        const val USB_TO_EMULATED = 0x10
        const val TUNER = 0x11
        const val CAB_SIZE = 0x13
        const val SPEAKER_TYPE = 0x14
        const val MIC_POSITION = 0x15
        const val MIC_TYPE = 0x16
        const val MIC_ANGLE = 0x17
        const val USB_TO_POWERAMP = 0x19
        const val NOISEGATE = 0x1A
        const val LINEIN_TO_POWERAMP = 0x1B
        const val FS_MODE = 0x1C
        const val FS_PATCH_A = 0x1D
        const val FS_PATCH_B = 0x1E
        const val SPEAKER_MUTE = 0x29
        const val NOISE_REDUCTION = 0x2F

        /** Settings the app is allowed to change. Anything else is refused. */
        val WRITABLE = setOf(SWS, MANUAL, EFFECTS_LOOP, LINEIN_TO_EMULATED, USB_TO_EMULATED, TUNER, CAB_SIZE, SPEAKER_TYPE,
            MIC_POSITION, MIC_TYPE, MIC_ANGLE, USB_TO_POWERAMP, NOISEGATE, LINEIN_TO_POWERAMP, FS_MODE, FS_PATCH_A, FS_PATCH_B,
            SPEAKER_MUTE, NOISE_REDUCTION)
    }

    // ---- outgoing ---------------------------------------------------------

    fun pad(vararg b: Int): ByteArray = ByteArray(PACKET).also { out -> b.forEachIndexed { i, v -> out[i] = v.toByte() } }

    /** Startup / "handshake": the amp answers with its full state, the selected patch and its mode. */
    fun requestState() = pad(0x81, 0x00, 0x00, 0x04, 0x03, 0x06, 0x02, 0x7A)

    fun setControl(c: Control, value: Int): ByteArray {
        val v = value.coerceIn(c.min, c.max)
        return if (c.twoBytes) pad(0x03, c.id, 0x00, 0x02, v and 0xFF, v shr 8)
        else pad(0x03, c.id, 0x00, 0x01, v)
    }

    fun setGlobal(id: Int, value: Int): ByteArray {
        require(id in Global.WRITABLE) { "global 0x%02X is not writable".format(id) }
        return pad(0x08, id, 0x00, 0x01, value and 0xFF)
    }

    fun selectPatch(slot: Int): ByteArray {
        require(slot in 1..SLOTS) { "slot must be 1..$SLOTS" }
        return pad(0x02, 0x01, slot, 0x00)
    }

    fun requestPatchName(slot: Int) = pad(0x02, 0x04, slot, 0x00)
    fun requestPatch(slot: Int) = pad(0x02, 0x05, slot, 0x00)

    fun cleanName(name: String) = name.uppercase().filter { it.code in 32..126 }.take(NAME_LEN).trim()

    /**
     * Packets that store a patch: a name packet, then the settings packet. [base] is the slot's
     * current 64-byte patch packet (from [requestPatch]); bytes we don't know are kept as they are.
     */
    fun storePackets(slot: Int, name: String, base: ByteArray, values: Map<Control, Int>): List<ByteArray> {
        require(slot in 1..SLOTS) { "slot must be 1..$SLOTS" }
        require(base.size == PACKET) { "base patch must be $PACKET bytes" }
        val namePkt = pad(0x02, 0x02, slot, 0x15)
        cleanName(name).forEachIndexed { i, ch -> namePkt[4 + i] = ch.code.toByte() }
        val set = base.copyOf()
        set[0] = 0x02; set[1] = 0x03; set[2] = slot.toByte(); set[3] = 0x29
        values.forEach { (c, raw) ->
            val v = raw.coerceIn(c.min, c.max)
            set[c.offset] = (v and 0xFF).toByte()
            if (c.twoBytes) set[c.offset + 1] = (v shr 8).toByte()
        }
        return listOf(namePkt, set)
    }

    // ---- incoming ---------------------------------------------------------

    sealed interface Event {
        data class FullState(val values: Map<Control, Int>) : Event
        data class ControlChanged(val control: Control, val value: Int) : Event
        data class PatchSelected(val slot: Int) : Event
        data class PatchName(val slot: Int, val name: String) : Event
        class PatchData(val slot: Int, val raw: ByteArray) : Event
        data class Manual(val on: Boolean) : Event
        data class Tuner(val on: Boolean) : Event
        data class TunerReading(val note: String, val cents: Int) : Event
        data class GlobalChanged(val id: Int, val value: Int) : Event
        class Unknown(val raw: ByteArray) : Event
    }

    val NOTES = listOf("", "E", "F", "F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#")

    fun decodeState(p: ByteArray): Map<Control, Int> = Control.entries.associateWith { c ->
        val lo = p[c.offset].u()
        if (c.twoBytes) lo or (p[c.offset + 1].u() shl 8) else lo
    }

    fun parse(p: ByteArray): Event {
        if (p.size < 5) return Event.Unknown(p)
        val b0 = p[0].u(); val b1 = p[1].u(); val b3 = p[3].u()
        return when {
            b0 == 0x03 && b3 == 0x2A -> Event.FullState(decodeState(p))
            b0 == 0x03 && (b3 == 0x01 || b3 == 0x02) -> {
                val c = Control.entries.firstOrNull { it.id == b1 } ?: return Event.Unknown(p)
                val v = if (b3 == 0x02) p[4].u() or (p[5].u() shl 8) else p[4].u()
                Event.ControlChanged(c, v)
            }
            b0 == 0x02 && b1 == 0x06 -> Event.PatchSelected(p[2].u())
            b0 == 0x02 && b1 == 0x04 -> Event.PatchName(p[2].u(), decodeName(p))
            b0 == 0x02 && b1 == 0x05 && p.size >= PACKET -> Event.PatchData(p[2].u(), p.copyOf(PACKET))
            b0 == 0x08 && b1 == Global.MANUAL -> Event.Manual(p[4].u() == 1)
            b0 == 0x08 && b1 == Global.TUNER -> Event.Tuner(p[4].u() == 1)
            b0 == 0x08 -> Event.GlobalChanged(b1, p[4].u())
            b0 == 0x09 -> Event.TunerReading(NOTES.getOrElse(b1) { "" }, p[2].u() - 50)
            else -> Event.Unknown(p)
        }
    }

    fun decodeName(p: ByteArray): String =
        p.copyOfRange(4, minOf(p.size, 4 + NAME_LEN + 1)).filter { it.u() in 32..126 }.map { it.u().toChar() }.joinToString("").trim()

    fun hex(b: ByteArray, max: Int = PACKET): String {
        var end = minOf(b.size, max)
        while (end > 4 && b[end - 1].toInt() == 0) end--   // trim zero padding so logs stay readable
        return b.take(end).joinToString(" ") { "%02X".format(it.u()) }
    }

    fun parseHex(s: String): ByteArray {
        val bytes = s.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.removePrefix("0x").removePrefix("0X").toInt(16) }
        require(bytes.size <= PACKET) { "max $PACKET bytes" }
        require(bytes.all { it in 0..255 }) { "bytes must be 00..FF" }
        return pad(*bytes.toIntArray())
    }

    private fun Byte.u() = toInt() and 0xFF
}
