package com.aravind.hendrifs.protocol

import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.protocol.IdCoreProtocol.Event
import com.aravind.hendrifs.protocol.IdCoreProtocol.Global
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte strings in these tests were captured from a real ID:Core Stereo 10 V4 and Architect 2.1.1 (research/V4-PROTOCOL.md). */
class IdCoreProtocolTest {
    private fun hex(p: ByteArray) = IdCoreProtocol.hex(p)
    private fun pkt(s: String) = IdCoreProtocol.parseHex(s)

    /** Real reports carry leftover memory after the declared length; the parser must ignore it. */
    private fun withJunk(s: String): ByteArray {
        val p = pkt(s); val used = s.trim().split(" ").size
        for (i in used until 64) p[i] = (0x5A + i).toByte()
        return p
    }

    private fun table(ev: Event.Params): IntArray = IntArray(64).also { t -> ev.bytes.forEachIndexed { i, v -> t[ev.first + i] = v } }

    @Test fun startupPacket() = assertEquals("81 00 00 04 03 06 02 7A", hex(IdCoreProtocol.requestState()))

    @Test fun setControlMatchesArchitect() {
        assertEquals("03 02 00 01 5A", hex(IdCoreProtocol.setControl(Control.GAIN, 90)))
        assertEquals("03 27 00 01 1E", hex(IdCoreProtocol.setControl(Control.GATE_AMOUNT, 30)))
        assertEquals("03 14 00 01 7F", hex(IdCoreProtocol.setControl(Control.MOD_DEPTH, 127)))
        assertEquals("03 1B 00 02 E8 03", hex(IdCoreProtocol.setControl(Control.DELAY_TIME, 1000)))
    }

    @Test fun setControlClampsToRange() {
        assertEquals("03 02 00 01 7F", hex(IdCoreProtocol.setControl(Control.GAIN, 500)))
        assertEquals("03 1B 00 02 E8 03", hex(IdCoreProtocol.setControl(Control.DELAY_TIME, 2000)))
    }

    @Test fun tunerOnOff() {
        assertEquals("08 11 00 01 01", hex(IdCoreProtocol.tuner(true)))
        assertEquals("08 11 00 01", hex(IdCoreProtocol.tuner(false)))   // trailing zero trimmed in hex
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownGlobalsAreRefused() { IdCoreProtocol.setGlobal(0x05, 1) }

    @Test fun globalWrites() = assertEquals("08 1C 00 01 01", hex(IdCoreProtocol.setGlobal(Global.FS_MODE, 1)))

    @Test fun fullStateFromStartup() {
        val ev = IdCoreProtocol.parse(withJunk("03 01 00 2A 01 3B 00 61 29 2E 39 00 2F 2A 7F 40 00 01 00 00 00 01 07 00 09 24 01 18 00 4E E0 00 03 04 00 3D 00 00 00 00 00 19 1E 00 00 00")) as Event.Params
        assertTrue(ev.whole)
        val v = IdCoreProtocol.decode(table(ev))
        assertEquals(1, v[Control.VOICE]); assertEquals(59, v[Control.GAIN]); assertEquals(97, v[Control.BASS])
        assertEquals(57, v[Control.ISF]); assertEquals(1, v[Control.MOD_TYPE]); assertEquals(224, v[Control.DELAY_TIME])
        assertEquals(3, v[Control.REVERB_TYPE]); assertEquals(61, v[Control.REVERB_LEVEL])
        assertEquals(25, v[Control.GATE_SENS]); assertEquals(30, v[Control.GATE_AMOUNT])
    }

    @Test fun singleKnobFromTheAmp() {
        val ev = IdCoreProtocol.parse(withJunk("03 02 00 01 3B")) as Event.Params
        assertEquals(0x02, ev.first); assertArrayEquals(intArrayOf(0x3B), ev.bytes); assertFalse(ev.whole)
    }

    @Test fun effectSwitchBlockCarriesOnOffAndFocus() {
        // DLY pressed on the amp: block from 0x10 (delay on) up to 0x24 (focus).
        val ev = IdCoreProtocol.parse(withJunk("03 10 00 15 01 00 01 07 00 09 24 01 18 00 4E E0 00 03 04 00 3D 00 00 00 02")) as Event.Params
        val v = IdCoreProtocol.decode(table(ev))
        assertEquals(1, v[Control.DELAY_ON]); assertEquals(0, v[Control.REVERB_ON]); assertEquals(2, v[Control.FX_FOCUS])
    }

    @Test fun globalsTableAndTuner() {
        val ev = IdCoreProtocol.parse(withJunk("08 01 00 2A 00 00 00 01 00 01 00 00 00 0A 01 00 00 00 00 00 01 00 01 00 00 02 00 00 00 18 00 00 01 04 00 00 00 00 00 19 19 00 19 19 00 00")) as Event.Globals
        assertEquals(1, ev.first); assertEquals(42, ev.bytes.size)
        assertEquals(1, ev.bytes[Global.TUNER - 1])          // tuner opened on the amp
        assertEquals(4, ev.bytes[Global.FS_PATCH_B - 1])
        val off = IdCoreProtocol.parse(withJunk("08 11 00 01 00")) as Event.Globals
        assertEquals(Global.TUNER, off.first); assertArrayEquals(intArrayOf(0), off.bytes)
    }

    @Test fun tunerReadings() {
        assertEquals(Event.TunerReading(1, "E", -22), IdCoreProtocol.parse(withJunk("09 01 1C 00 12 78 28 42")))
        assertEquals(Event.TunerReading(0, "", 0), IdCoreProtocol.parse(withJunk("09 00 00 00 C4 57 0F 42")))
    }

    @Test fun patchSelectedCarriesTheSound() {
        val ev = IdCoreProtocol.parse(withJunk("02 06 03 3C 02 46 2C 61 29 2E 41 04 3D 64 7F 40 00 01 00 00 00 01 07 00 09 24 01 18 00 4E E0 00 03 04 00 3D 00 00 00 00 00 19 1E")) as Event.PatchSelected
        assertEquals(3, ev.slot); assertEquals(2, ev.bytes[0]); assertEquals(0x46, ev.bytes[1])
    }

    @Test fun patchNameStopsAtNul() =
        assertEquals(Event.PatchName(1, "Classic Clean"),
            IdCoreProtocol.parse(withJunk("02 04 01 15 43 6C 61 73 73 69 63 20 43 6C 65 61 6E 00 00 00 00 00 00 00 00 CC 1D 00 01")))

    @Test fun versionReport() = assertEquals(Event.Version("1.0.4"), IdCoreProtocol.parse(withJunk("07 00 00 03 01 00 04")))

    @Test fun storeMatchesArchitectByteForByte() {
        // Patch 1 as read from the amp (02 05 01 2A ...), then stored back exactly as Architect does it.
        val read = IdCoreProtocol.parse(withJunk("02 05 01 2A 00 4C 41 5E 1E 33 33 00 00 00 58 31 00 01 00 00 01 03 0B 01 1F 1C 02 17 00 6F 6A 01 03 11 00 23 00 00 00 03 00 1D 12 00 00 00")) as Event.PatchData
        val t = IntArray(64).also { tb -> read.bytes.forEachIndexed { i, v -> tb[i + 1] = v } }
        val (name, set) = IdCoreProtocol.storePackets(1, "Classic Clean", t)
        assertEquals("02 02 01 0D 43 6C 61 73 73 69 63 20 43 6C 65 61 6E", hex(name))
        // Volume (0x41) must survive: a 0 there silences the patch.
        assertEquals("02 03 01 2B 00 4C 41 5E 1E 33 33 00 00 00 58 31 00 01 00 00 01 03 0B 01 1F 1C 02 17 00 6F 6A 01 03 11 00 23 00 00 00 03 00 1D 12", hex(set))
    }

    @Test fun unknownPacketsAreKeptRaw() = assertTrue(IdCoreProtocol.parse(withJunk("B0 00 00 04 26 50 50 41")) is Event.Unknown)

    @Test fun parseHexAcceptsSpacesCommasAnd0x() =
        assertEquals("81 00 00 04 03 06 02 7A", hex(IdCoreProtocol.parseHex("81 0x00,00 04 03 06 02 7a")))
}
