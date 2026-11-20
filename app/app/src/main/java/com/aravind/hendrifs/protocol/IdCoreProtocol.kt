package com.aravind.hendrifs.protocol

/**
 * Blackstar ID:Core V4 USB HID protocol: 64-byte reports, report id 0.
 *
 * Confirmed on a real ID:Core Stereo 10 V4 (firmware 1.0.4) against Blackstar Architect 2.1.1,
 * see research/V4-PROTOCOL.md. The core rule: `<kind> <first id> 00 <count> <values...>`, where kind 03 is
 * the sound (patch parameters) and kind 08 the amp-wide settings. Only the first 4 + count bytes mean anything.
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
    /** Parameter ids 1..43 make up a stored sound (`02 03 <slot> 2B` and `03 01 00 2B`). */
    const val SOUND_LEN = 0x2B
    /** Size of the parameter tables we keep (ids 0..63). */
    const val TABLE = 64

    val VOICES = listOf("Clean Warm", "Clean Bright", "Crunch", "Super Crunch", "OD 1", "OD 2")
    val MOD_TYPES = listOf("Phaser", "Chorus/Flanger", "Envelope", "Tremolo")
    val DELAY_TYPES = listOf("Linear", "Analogue", "Tape", "Multi")
    val REVERB_TYPES = listOf("Room", "Hall", "Spring", "Plate")

    /** Patch parameters. The value of control [id] is table[id]; delay time also uses table[id + 1]. */
    enum class Control(val id: Int, val min: Int, val max: Int, val twoBytes: Boolean = false) {
        VOICE(0x01, 0, 5),
        GAIN(0x02, 0, 127),
        VOLUME(0x03, 0, 127),
        BASS(0x04, 0, 127),
        MIDDLE(0x05, 0, 127),
        TREBLE(0x06, 0, 127),
        ISF(0x07, 0, 127),
        TVP(0x08, 0, 127),
        RESONANCE(0x0B, 0, 127),
        PRESENCE(0x0C, 0, 127),
        TVP_ON(0x0E, 0, 1),
        MOD_ON(0x0F, 0, 1),
        DELAY_ON(0x10, 0, 1),
        REVERB_ON(0x11, 0, 1),
        MOD_TYPE(0x12, 0, 3),
        MOD_SEGVAL(0x13, 0, 31),      // Adjust 1: Morph / manual
        MOD_DEPTH(0x14, 0, 127),      // Adjust 2: Depth
        MOD_LEVEL(0x15, 0, 127),      // Mix
        MOD_SPEED(0x16, 0, 127),
        DELAY_TYPE(0x17, 0, 3),
        DELAY_FEEDBACK(0x18, 0, 31),
        DELAY_ADJ2(0x19, 0, 127),
        DELAY_LEVEL(0x1A, 0, 127),
        DELAY_TIME(0x1B, 100, 1000, twoBytes = true),
        REVERB_TYPE(0x1D, 0, 3),
        REVERB_SIZE(0x1E, 0, 31),
        REVERB_ADJ2(0x1F, 0, 127),
        REVERB_LEVEL(0x20, 0, 127),
        FX_FOCUS(0x24, 0, 3),         // 0 none, 1 mod, 2 delay, 3 reverb
        GATE_SENS(0x26, 0, 127),
        GATE_AMOUNT(0x27, 0, 127);

        companion object {
            fun byName(n: String) = entries.firstOrNull { it.name == n }
            fun byId(id: Int) = entries.firstOrNull { it.id == id }
        }
    }

    /** Amp-wide settings (`08 <id> ...`), numbered as in the V4 firmware's settings table. */
    object Global {
        const val SWS = 0x01
        const val MANUAL = 0x03
        const val EFFECTS_LOOP = 0x0E
        const val LINEIN_TO_EMULATED = 0x0F
        const val USB_TO_EMULATED = 0x10
        const val TUNER = 0x11
        const val TUNER_MODE = 0x12
        const val CAB_SIZE = 0x13
        const val SPEAKER_TYPE = 0x14
        const val MIC_POSITION = 0x15
        const val MIC_TYPE = 0x16
        const val MIC_ANGLE = 0x17
        const val USB_TO_POWERAMP = 0x19
        const val LINEIN_TO_POWERAMP = 0x1B
        const val FS_MODE = 0x1C
        const val FS_PATCH_A = 0x1D
        const val FS_PATCH_B = 0x1E
        const val SPEAKER_MUTE = 0x29
        const val NOISE_REDUCTION = 0x2F

        /** Settings the app is allowed to change. Anything else is refused. */
        val WRITABLE = setOf(SWS, TUNER, TUNER_MODE, CAB_SIZE, SPEAKER_TYPE, MIC_POSITION, MIC_TYPE, MIC_ANGLE,
            FS_MODE, FS_PATCH_A, FS_PATCH_B)
    }

    // ---- outgoing ---------------------------------------------------------

    fun pad(vararg b: Int): ByteArray = ByteArray(PACKET).also { out -> b.forEachIndexed { i, v -> out[i] = v.toByte() } }

    /** Startup: the amp answers with its version, the live sound, all settings and the selected patch. */
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

    fun tuner(on: Boolean) = pad(0x08, Global.TUNER, 0x00, 0x01, if (on) 1 else 0)

    fun selectPatch(slot: Int): ByteArray {
        require(slot in 1..SLOTS) { "slot must be 1..$SLOTS" }
        return pad(0x02, 0x01, slot, 0x00)
    }

    fun requestPatchName(slot: Int) = pad(0x02, 0x04, slot, 0x00)
    fun requestPatch(slot: Int) = pad(0x02, 0x05, slot, 0x00)

    /** Names keep their case (the amp stores "Classic Clean"); only printable ASCII, 20 characters. */
    fun cleanName(name: String) = name.filter { it.code in 32..126 }.take(NAME_LEN).trim()

    /** Table of parameter bytes (index = id) built from control values on top of [base]. */
    fun encode(values: Map<Control, Int>, base: IntArray = IntArray(TABLE)): IntArray {
        val t = base.copyOf(TABLE)
        values.forEach { (c, raw) ->
            val v = raw.coerceIn(c.min, c.max)
            t[c.id] = v and 0xFF
            if (c.twoBytes) t[c.id + 1] = v shr 8
        }
        return t
    }

    fun decode(t: IntArray): Map<Control, Int> = Control.entries.associateWith { c ->
        if (c.twoBytes) t[c.id] or (t[c.id + 1] shl 8) else t[c.id]
    }

    /**
     * The 43 parameter bytes of a stored sound. Volume IS part of a V4 patch (patches read back with 0x22, 0x41, ...);
     * a 0 here makes a silent patch, so callers must fill it in.
     */
    private fun soundBody(t: IntArray) = IntArray(SOUND_LEN) { i -> t[i + 1] }

    /** Stores a sound in [slot]: name packet, then the 43 parameter bytes (the format Architect uses). */
    fun storePackets(slot: Int, name: String, t: IntArray): List<ByteArray> {
        require(slot in 1..SLOTS) { "slot must be 1..$SLOTS" }
        val n = cleanName(name).ifEmpty { "Patch $slot" }
        return listOf(
            pad(0x02, 0x02, slot, n.length, *n.map { it.code }.toIntArray()),
            pad(0x02, 0x03, slot, SOUND_LEN, *soundBody(t)),
        )
    }

    // ---- incoming ---------------------------------------------------------

    sealed interface Event {
        /** Parameter bytes for ids first, first + 1, ... (any `03` block: one knob, an effect switch, the whole sound). */
        class Params(val first: Int, val bytes: IntArray, val whole: Boolean) : Event
        /** Settings bytes for ids first, first + 1, ... (`08` block). */
        class Globals(val first: Int, val bytes: IntArray) : Event
        class PatchSelected(val slot: Int, val bytes: IntArray) : Event
        data class PatchName(val slot: Int, val name: String) : Event
        class PatchData(val slot: Int, val bytes: IntArray) : Event
        data class Version(val text: String) : Event
        /** [note] is the amp's note number (0 = no signal), [cents] -50..+50. */
        data class TunerReading(val note: Int, val name: String, val cents: Int) : Event
        class Unknown(val raw: ByteArray) : Event
    }

    /** Note numbers of the amp's tuner reports. */
    val NOTES = listOf("", "E", "F", "F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#")

    private fun Byte.u() = toInt() and 0xFF

    /** Values of a block, never reading past the declared count or the packet. */
    private fun block(p: ByteArray, count: Int) = IntArray(minOf(count, p.size - 4)) { p[4 + it].u() }

    fun parse(p: ByteArray): Event {
        if (p.size < 4) return Event.Unknown(p)
        val b0 = p[0].u(); val b1 = p[1].u(); val b2 = p[2].u(); val n = p[3].u()
        return when {
            b0 == 0x03 && b2 == 0 && n in 1..60 && b1 + n <= TABLE -> Event.Params(b1, block(p, n), b1 == 1 && n >= 0x29)
            b0 == 0x08 && b2 == 0 && n in 1..60 && b1 + n <= TABLE -> Event.Globals(b1, block(p, n))
            b0 == 0x02 && b1 == 0x06 -> Event.PatchSelected(b2, block(p, n.coerceAtMost(SOUND_LEN)))
            b0 == 0x02 && b1 == 0x04 -> Event.PatchName(b2, decodeName(p))
            b0 == 0x02 && b1 == 0x05 -> Event.PatchData(b2, block(p, n.coerceAtMost(SOUND_LEN)))
            b0 == 0x07 -> Event.Version(block(p, n).joinToString("."))
            b0 == 0x09 -> Event.TunerReading(b1, NOTES.getOrElse(b1) { "" }, if (b1 == 0) 0 else (b2 - 50).coerceIn(-50, 50))
            else -> Event.Unknown(p)
        }
    }

    /** Name up to the first NUL (the bytes after it are leftover memory). */
    fun decodeName(p: ByteArray): String {
        val n = minOf(p[3].u().takeIf { it > 0 } ?: (NAME_LEN + 1), NAME_LEN + 1, p.size - 4)
        val sb = StringBuilder()
        for (i in 0 until n) { val c = p[4 + i].u(); if (c == 0) break; if (c in 32..126) sb.append(c.toChar()) }
        return sb.toString().trim()
    }

    /** Hex of the meaningful part of a report: header plus declared count where that applies. */
    fun hex(b: ByteArray, max: Int = PACKET): String {
        var end = minOf(b.size, max)
        val kind = if (b.isNotEmpty()) b[0].u() else 0
        if (b.size >= 4 && kind in setOf(0x02, 0x03, 0x07, 0x08) && max == PACKET) end = minOf(end, 4 + b[3].u())
        while (end > 4 && b[end - 1].toInt() == 0) end--
        return b.take(end).joinToString(" ") { "%02X".format(it.u()) }
    }

    fun parseHex(s: String): ByteArray {
        val bytes = s.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.removePrefix("0x").removePrefix("0X").toInt(16) }
        require(bytes.size <= PACKET) { "max $PACKET bytes" }
        require(bytes.all { it in 0..255 }) { "bytes must be 00..FF" }
        return pad(*bytes.toIntArray())
    }
}
