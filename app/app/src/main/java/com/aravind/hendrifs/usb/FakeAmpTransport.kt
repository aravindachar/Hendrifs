package com.aravind.hendrifs.usb

import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.protocol.IdCoreProtocol.Global
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * A simulated ID:Core V4 that answers with the formats captured from a real amp (research/V4-PROTOCOL.md):
 * value blocks, echoes of every command, patch select with its sound, tuner stream. Lets the app run without hardware.
 */
class FakeAmpTransport : AmpTransport {
    override val description = "Demo amp (simulated)"

    private val replies = LinkedBlockingQueue<ByteArray>()
    private val names = mutableListOf("Classic Clean", "Boutique Clean", "Classic Crunch", "Modern Drive", "Smooth Solo", "Tight Metal Rhythm")
    private val globals = IntArray(IdCoreProtocol.TABLE).also { it[Global.FS_PATCH_A] = 1; it[Global.FS_PATCH_B] = 4 }
    private var slot = 2
    private var tunerString = 1
    private var tunerCents = 18.0
    private var lastTunerTick = 0L

    private val patches = Array(IdCoreProtocol.SLOTS) { i ->
        IdCoreProtocol.encode(mapOf(
            Control.VOICE to i, Control.GAIN to listOf(30, 59, 70, 95, 70, 127)[i], Control.VOLUME to 50,
            Control.BASS to 97, Control.MIDDLE to 41, Control.TREBLE to 46, Control.ISF to 57,
            Control.RESONANCE to 127, Control.PRESENCE to 64,
            Control.DELAY_ON to (i % 2), Control.REVERB_ON to 1, Control.FX_FOCUS to 3,
            Control.MOD_TYPE to 1, Control.MOD_SEGVAL to 7, Control.MOD_LEVEL to 9, Control.MOD_SPEED to 36, Control.MOD_DEPTH to 40,
            Control.DELAY_TYPE to 1, Control.DELAY_FEEDBACK to 24, Control.DELAY_LEVEL to 78, Control.DELAY_TIME to 400,
            Control.REVERB_TYPE to 3, Control.REVERB_SIZE to 4, Control.REVERB_LEVEL to 61,
            Control.GATE_SENS to 25, Control.GATE_AMOUNT to (if (i >= 4) 60 else 30),
        ))
    }
    private val live = patches[slot - 1].copyOf()

    private fun block(kind: Int, first: Int, t: IntArray, count: Int, b2: Int = 0) =
        IdCoreProtocol.pad(kind, first, b2, count, *IntArray(count) { t[first + it] })

    override fun write(packet: ByteArray) {
        val b0 = packet[0].toInt() and 0xFF
        val b1 = packet[1].toInt() and 0xFF
        val b2 = packet[2].toInt() and 0xFF
        val n = packet[3].toInt() and 0xFF
        when {
            b0 == 0x81 -> {
                replies.put(IdCoreProtocol.pad(0x07, 0x00, 0x00, 0x03, 0x01, 0x00, 0x04))
                replies.put(block(0x03, 1, live.copyOf().also { it[Control.VOLUME.id] = 0 }, 0x2A))
                replies.put(block(0x08, 1, globals, 0x2A))
                replies.put(IdCoreProtocol.pad(0x02, 0x06, slot, 0x3C, *IntArray(0x2B) { patches[slot - 1][it + 1] }))
            }
            b0 == 0x03 -> {
                for (i in 0 until n) live[b1 + i] = packet[4 + i].toInt() and 0xFF
                replies.put(packet.copyOf())                                        // the real amp echoes everything
            }
            b0 == 0x08 -> {
                for (i in 0 until n) globals[b1 + i] = packet[4 + i].toInt() and 0xFF
                if (b1 == Global.TUNER && globals[Global.TUNER] == 0) replies.put(packet.copyOf())
            }
            b0 == 0x02 && b1 == 0x01 && b2 in 1..IdCoreProtocol.SLOTS -> select(b2)
            b0 == 0x02 && b1 == 0x02 -> names[b2 - 1] = IdCoreProtocol.decodeName(packet)
            b0 == 0x02 && b1 == 0x03 -> for (i in 0 until n) patches[b2 - 1][i + 1] = packet[4 + i].toInt() and 0xFF
            b0 == 0x02 && b1 == 0x04 -> {
                val name = names.getOrElse(b2 - 1) { "" }
                replies.put(IdCoreProtocol.pad(0x02, 0x04, b2, 0x15, *name.map { it.code }.toIntArray()))
            }
            b0 == 0x02 && b1 == 0x05 -> replies.put(IdCoreProtocol.pad(0x02, 0x05, b2, 0x2A, *IntArray(0x2A) { patches[b2 - 1][it + 1] }))
        }
    }

    private fun select(s: Int) {
        slot = s
        val vol = live[Control.VOLUME.id]
        patches[s - 1].copyInto(live); live[Control.VOLUME.id] = vol
        replies.put(IdCoreProtocol.pad(0x02, 0x06, s, 0x3C, *IntArray(0x2B) { patches[s - 1][it + 1] }))
        replies.put(block(0x03, 1, live.copyOf().also { it[Control.VOLUME.id] = 0 }, 0x29))
    }

    override fun read(): ByteArray? {
        replies.poll(8, TimeUnit.MILLISECONDS)?.let { return it }
        // While the tuner is on, report a slowly drifting note like a guitar being tuned.
        if (globals[Global.TUNER] == 1 && System.currentTimeMillis() - lastTunerTick > 100) {
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
