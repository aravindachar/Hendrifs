package com.aravind.hendrifs.protocol

import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.protocol.IdCoreProtocol.Event
import com.aravind.hendrifs.protocol.IdCoreProtocol.Global
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCoreProtocolTest {
    private fun hex(p: ByteArray) = IdCoreProtocol.hex(p)

    @Test fun startupPacket() = assertEquals("81 00 00 04 03 06 02 7A", hex(IdCoreProtocol.requestState()))

    @Test fun setControlSingleByte() {
        val p = IdCoreProtocol.setControl(Control.GAIN, 90)
        assertEquals(64, p.size)
        assertEquals("03 02 00 01 5A", hex(p))
    }

    @Test fun setControlClampsToRange() = assertEquals("03 02 00 01 7F", hex(IdCoreProtocol.setControl(Control.GAIN, 500)))

    @Test fun delayTimeIsTwoBytesLittleEndian() = assertEquals("03 1B 00 02 90 01", hex(IdCoreProtocol.setControl(Control.DELAY_TIME, 400)))

    @Test fun resonanceAndPresenceIds() {
        assertEquals("03 0B 00 01 40", hex(IdCoreProtocol.setControl(Control.RESONANCE, 64)))
        assertEquals("03 0C 00 01 40", hex(IdCoreProtocol.setControl(Control.PRESENCE, 64)))
    }

    @Test fun globalsUseThe08Family() {
        assertEquals("08 11 00 01 01", hex(IdCoreProtocol.setGlobal(Global.TUNER, 1)))
        assertEquals("08 03 00 01 01", hex(IdCoreProtocol.setGlobal(Global.MANUAL, 1)))
        assertEquals("08 1C 00 01 01", hex(IdCoreProtocol.setGlobal(Global.FS_MODE, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownGlobalsAreRefused() { IdCoreProtocol.setGlobal(0x05, 1) }   // software version: read only

    @Test fun selectAndReadPatch() {
        assertEquals("02 01 04 00", hex(IdCoreProtocol.selectPatch(4)))
        assertEquals("02 05 03 00", hex(IdCoreProtocol.requestPatch(3)))
    }

    @Test fun fullStateRoundTrip() {
        val p = IdCoreProtocol.pad(0x03, 0x01, 0x00, 0x2A)
        p[Control.VOICE.offset] = 3
        p[Control.GAIN.offset] = 100
        p[Control.DELAY_TIME.offset] = 0x90.toByte(); p[Control.DELAY_TIME.offset + 1] = 0x01
        val ev = IdCoreProtocol.parse(p) as Event.FullState
        assertEquals(3, ev.values[Control.VOICE])
        assertEquals(100, ev.values[Control.GAIN])
        assertEquals(400, ev.values[Control.DELAY_TIME])
    }

    @Test fun ampEvents() {
        assertEquals(Event.ControlChanged(Control.TREBLE, 64), IdCoreProtocol.parse(IdCoreProtocol.pad(0x03, 0x06, 0x00, 0x01, 0x40)))
        assertEquals(Event.PatchName(2, "CLEAN"), IdCoreProtocol.parse(IdCoreProtocol.pad(0x02, 0x04, 0x02, 0x15, 'C'.code, 'L'.code, 'E'.code, 'A'.code, 'N'.code)))
        assertEquals(Event.PatchSelected(5), IdCoreProtocol.parse(IdCoreProtocol.pad(0x02, 0x06, 0x05, 0x00, 0)))
        assertEquals(Event.Manual(true), IdCoreProtocol.parse(IdCoreProtocol.pad(0x08, 0x03, 0x00, 0x01, 0x01)))
        assertEquals(Event.Tuner(true), IdCoreProtocol.parse(IdCoreProtocol.pad(0x08, 0x11, 0x00, 0x01, 0x01)))
        assertEquals(Event.GlobalChanged(0x04, 3), IdCoreProtocol.parse(IdCoreProtocol.pad(0x08, 0x04, 0x00, 0x01, 0x03)))
        assertEquals(Event.TunerReading("A", -8), IdCoreProtocol.parse(IdCoreProtocol.pad(0x09, 0x06, 42, 0x00, 0)))
    }

    @Test fun patchDataIsKeptRaw() {
        val ev = IdCoreProtocol.parse(IdCoreProtocol.pad(0x02, 0x05, 0x03, 0x2A, 0x02)) as Event.PatchData
        assertEquals(3, ev.slot)
        assertEquals(64, ev.raw.size)
    }

    @Test fun storePacketsMergeIntoTheBasePatch() {
        val base = IdCoreProtocol.pad(0x02, 0x05, 0x02, 0x2A).also { it[40] = 0x55 }   // an unknown byte must survive
        val (name, set) = IdCoreProtocol.storePackets(2, "my crunch!", base, mapOf(Control.GAIN to 99, Control.DELAY_TIME to 500))
        assertEquals("02 02 02 15 4D 59 20 43 52 55 4E 43 48 21", hex(name))
        assertEquals(0x02, set[0].toInt()); assertEquals(0x03, set[1].toInt()); assertEquals(2, set[2].toInt()); assertEquals(0x29, set[3].toInt())
        assertEquals(99, set[Control.GAIN.offset].toInt())
        assertEquals(500, (set[Control.DELAY_TIME.offset].toInt() and 0xFF) or (set[Control.DELAY_TIME.offset + 1].toInt() shl 8))
        assertEquals(0x55, set[40].toInt())
    }

    @Test fun unknownPacketsAreKeptRaw() = assertTrue(IdCoreProtocol.parse(IdCoreProtocol.pad(0x55, 0x01)) is Event.Unknown)

    @Test fun parseHexAcceptsSpacesCommasAnd0x() =
        assertEquals("81 00 00 04 03 06 02 7A", hex(IdCoreProtocol.parseHex("81 0x00,00 04 03 06 02 7a")))
}
