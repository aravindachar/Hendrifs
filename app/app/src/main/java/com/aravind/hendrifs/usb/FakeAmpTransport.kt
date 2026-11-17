package com.aravind.hendrifs.usb

import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.protocol.IdCoreProtocol.Global
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/** A simulated ID:Core that answers like the real protocol, so the whole app can be tried without hardware. */
class FakeAmpTransport : AmpTransport {
    override val description = "Demo amp (simulated)"

    private val replies = LinkedBlockingQueue<ByteArray>()
    private val names = mutableListOf("CLEAN ONE", "CLEAN ARP", "CRUNCH 80S", "SUPER CRUNCH", "LEAD", "HIGH GAIN")
    private val globals = mutableMapOf(Global.SWS to 1, Global.MANUAL to 0, Global.TUNER to 0, Global.NOISEGATE to 0)
    private var slot = 3
    private var tunerString = 1
    private var tunerCents = 18.0
    private var lastTunerTick = 0L

    private val presets = Array(IdCoreProtocol.SLOTS) { i ->
        IdCoreProtocol.pad(0x02, 0x05, i + 1, 0x2A).also { p ->
            fun put(c: Control, v: Int) { p[c.offset] = (v and 0xFF).toByte(); if (c.twoBytes) p[c.offset + 1] = (v shr 8).toByte() }
            put(Control.VOICE, i); put(Control.GAIN, listOf(20, 30, 70, 90, 100, 115)[i]); put(Control.VOLUME, 50)
            put(Control.BASS, 72); put(Control.MIDDLE, 58); put(Control.TREBLE, 80); put(Control.ISF, 64)
            put(Control.RESONANCE, 50); put(Control.PRESENCE, 76)
            put(Control.DELAY_ON, 1); put(Control.REVERB_ON, 1); put(Control.FX_FOCUS, 3)
            put(Control.DELAY_TYPE, 1); put(Control.DELAY_FEEDBACK, 10); put(Control.DELAY_LEVEL, 40); put(Control.DELAY_TIME, 400)
            put(Control.REVERB_SIZE, 12); put(Control.REVERB_LEVEL, 40); put(Control.MOD_LEVEL, 60); put(Control.MOD_SPEED, 50)
        }
    }
    private val live = presets[slot - 1].copyOf().also { it[0] = 0x03; it[1] = 0x01; it[2] = 0x00; it[3] = 0x2A }

    override fun write(packet: ByteArray) {
        val b0 = packet[0].toInt() and 0xFF
        val b1 = packet[1].toInt() and 0xFF
        val b2 = packet[2].toInt() and 0xFF
        when {
            b0 == 0x81 -> {
                replies.put(live.copyOf())
                replies.put(IdCoreProtocol.pad(0x08, Global.PATCH_NUMBER, 0x00, 0x01, slot))
                replies.put(IdCoreProtocol.pad(0x08, Global.MANUAL, 0x00, 0x01, globals[Global.MANUAL] ?: 0))
            }
            b0 == 0x03 -> {
                live[b1 + 3] = packet[4]
                if ((packet[3].toInt() and 0xFF) == 0x02) live[b1 + 4] = packet[5]
            }
            b0 == 0x08 -> {
                globals[b1] = packet[4].toInt() and 0xFF
                replies.put(IdCoreProtocol.pad(0x08, b1, 0x00, 0x01, globals[b1]!!))
            }
            b0 == 0x02 && b1 == 0x01 -> {
                slot = b2
                presets[slot - 1].copyInto(live, 4, 4, 64)
                replies.put(IdCoreProtocol.pad(0x02, 0x06, slot, 0x00))
                replies.put(live.copyOf())
            }
            b0 == 0x02 && b1 == 0x02 -> names[b2 - 1] = IdCoreProtocol.decodeName(packet)
            b0 == 0x02 && b1 == 0x03 -> {
                packet.copyInto(presets[b2 - 1], 4, 4, 64)
                replies.put(IdCoreProtocol.pad(0x02, 0x03, b2, 0x00))
            }
            b0 == 0x02 && b1 == 0x04 -> {
                val p = IdCoreProtocol.pad(0x02, 0x04, b2, 0x15)
                names.getOrElse(b2 - 1) { "" }.forEachIndexed { i, ch -> p[4 + i] = ch.code.toByte() }
                replies.put(p)
            }
            b0 == 0x02 && b1 == 0x05 -> replies.put(presets[b2 - 1].copyOf())
        }
    }

    override fun read(): ByteArray? {
        replies.poll(40, TimeUnit.MILLISECONDS)?.let { return it }
        // While the tuner is on, report a slowly drifting note like a guitar being tuned.
        if (globals[Global.TUNER] == 1 && System.currentTimeMillis() - lastTunerTick > 120) {
            lastTunerTick = System.currentTimeMillis()
            tunerCents += (Random.nextDouble() - 0.6) * 5
            if (abs(tunerCents) < 1.5 && Random.nextDouble() < 0.12) { tunerString = (tunerString + 1) % 6; tunerCents = (Random.nextDouble() - .5) * 60 }
            tunerCents = tunerCents.coerceIn(-50.0, 50.0)
            val note = IdCoreProtocol.NOTES.indexOf(listOf("E", "A", "D", "G", "B", "E")[tunerString])
            return IdCoreProtocol.pad(0x09, note, (tunerCents + 50).toInt(), 0x00)
        }
        return null
    }

    override fun close() {}
}
