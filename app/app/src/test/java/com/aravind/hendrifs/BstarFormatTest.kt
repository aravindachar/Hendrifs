package com.aravind.hendrifs

import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import org.junit.Assert.assertEquals
import org.junit.Test

class BstarFormatTest {
    private fun sample() = BstarFormat.parse(javaClass.classLoader!!.getResourceAsStream("sample.bstar")!!)

    @Test fun readsArchitectPatch() {
        val p = sample()
        assertEquals("SAMPLE PATCH", p.name)
        assertEquals(2, p.values[Control.VOICE]); assertEquals(64, p.values[Control.GAIN]); assertEquals(90, p.values[Control.BASS])
        assertEquals(1, p.values[Control.DELAY_TYPE]); assertEquals(384, p.values[Control.DELAY_TIME]); assertEquals(2, p.values[Control.REVERB_TYPE])
        assertEquals(null, p.values[Control.VOLUME])   // the amp's volume knob is never taken from a patch
    }

    @Test fun writeThenReadIsTheSame() {
        val p = sample()
        val xml = BstarFormat.write(p.name, "Blues", p.values)
        val back = BstarFormat.parse(xml.byteInputStream())
        for (c in listOf(Control.VOICE, Control.GAIN, Control.ISF, Control.DELAY_TIME, Control.REVERB_LEVEL, Control.MOD_ON))
            assertEquals(c.name, p.values[c] ?: 0, back.values[c] ?: 0)
        assertEquals("Blues", back.genre)
    }
}
