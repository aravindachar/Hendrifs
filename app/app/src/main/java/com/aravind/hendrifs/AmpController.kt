package com.aravind.hendrifs

import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.protocol.IdCoreProtocol.Event
import com.aravind.hendrifs.usb.AmpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

data class LogLine(val time: String, val outgoing: Boolean, val hex: String, val note: String) {
    override fun toString() = "$time ${if (outgoing) "OUT" else " IN"} $hex${if (note.isNotEmpty()) "   # $note" else ""}"
}

data class AmpState(
    val connected: Boolean = false,
    val demo: Boolean = false,
    val link: String = "No amp",
    val values: Map<Control, Int> = emptyMap(),
    val globals: Map<Int, Int> = emptyMap(),
    val presets: Map<Int, ByteArray> = emptyMap(),
    val slot: Int? = null,
    val names: Map<Int, String> = emptyMap(),
    val manual: Boolean = false,
    val tunerOn: Boolean = false,
    val tunerNote: String = "",
    val tunerCents: Int = 0,
    val message: String? = null,
    val log: List<LogLine> = emptyList(),
)

/** Owns the worker thread: every USB read and write happens there, in order. */
class AmpController {
    private val _state = MutableStateFlow(AmpState())
    val state: StateFlow<AmpState> = _state

    private val outbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var pendingStore: Triple<Int, String, Map<Control, Int>>? = null

    fun connect(transport: AmpTransport, demo: Boolean) {
        disconnect()
        outbox.clear()
        running = true
        _state.update { AmpState(connected = true, demo = demo, link = transport.description, log = it.log) }
        log(true, ByteArray(0), "connected: ${transport.description}")
        worker = Thread({ loop(transport) }, "amp-usb").also { it.start() }
        send(IdCoreProtocol.requestState())
        for (s in 1..IdCoreProtocol.SLOTS) send(IdCoreProtocol.requestPatchName(s))
        for (s in 1..IdCoreProtocol.SLOTS) send(IdCoreProtocol.requestPatch(s))
    }

    fun disconnect() {
        running = false
        worker?.let { it.interrupt(); it.join(500) }
        worker = null
        _state.update { it.copy(connected = false, demo = false, link = "No amp", tunerOn = false) }
    }

    fun send(packet: ByteArray) { if (running) outbox.put(packet) }

    /** Changes a control live and updates our copy at once so the UI does not jump. */
    fun set(c: Control, value: Int) {
        val v = value.coerceIn(c.min, c.max)
        _state.update { it.copy(values = it.values + (c to v)) }
        send(IdCoreProtocol.setControl(c, v))
    }

    fun setGlobal(id: Int, value: Int) {
        runCatching { IdCoreProtocol.setGlobal(id, value) }
            .onSuccess { pkt -> _state.update { it.copy(globals = it.globals + (id to value)) }; send(pkt) }
            .onFailure { e -> say(e.message ?: "refused") }
    }

    /** The amp's own rule for MOD / DLY / REV: off → on + focus, on → focus, focused → off. */
    fun stomp(fx: Int) {
        val onC = listOf(Control.MOD_ON, Control.DELAY_ON, Control.REVERB_ON)
        require(fx in 1..3)
        val v = _state.value.values
        val isOn = (v[onC[fx - 1]] ?: 0) == 1
        val focus = v[Control.FX_FOCUS] ?: 0
        when {
            !isOn -> { set(onC[fx - 1], 1); set(Control.FX_FOCUS, fx) }
            focus != fx -> set(Control.FX_FOCUS, fx)
            else -> {
                set(onC[fx - 1], 0)
                val other = (1..3).firstOrNull { it != fx && (v[onC[it - 1]] ?: 0) == 1 } ?: 0
                set(Control.FX_FOCUS, other)
            }
        }
    }

    fun selectPatch(slot: Int) {
        _state.update { it.copy(slot = slot, manual = false) }
        send(IdCoreProtocol.selectPatch(slot))
        send(IdCoreProtocol.requestState())
    }

    fun refresh() {
        send(IdCoreProtocol.requestState())
        for (s in 1..IdCoreProtocol.SLOTS) send(IdCoreProtocol.requestPatch(s))
    }

    /** Applies a saved sound live (volume is global, so it is left alone). */
    fun apply(values: Map<Control, Int>) {
        values[Control.VOICE]?.let { set(Control.VOICE, it) }
        values.filterKeys { it != Control.VOICE && it != Control.VOLUME }.forEach { (c, v) -> set(c, v) }
    }

    /** Stores the current live sound in [slot] under [name], then reads it back to confirm. */
    fun storeCurrent(slot: Int, name: String) {
        val values = _state.value.values.filterKeys { it != Control.VOLUME }
        store(slot, name, values, null)
    }

    fun restore(slot: Int, name: String, raw: ByteArray) = store(slot, name, IdCoreProtocol.decodeState(raw), raw)

    private fun store(slot: Int, name: String, values: Map<Control, Int>, raw: ByteArray?) {
        val base = raw ?: _state.value.presets[slot]
        if (base == null) { say("Patch $slot has not been read yet. Tap Refresh and try again."); refresh(); return }
        val (namePkt, setPkt) = IdCoreProtocol.storePackets(slot, name, base, values)
        pendingStore = Triple(slot, IdCoreProtocol.cleanName(name), values)
        send(namePkt); send(setPkt)
        send(IdCoreProtocol.requestPatch(slot)); send(IdCoreProtocol.requestPatchName(slot))
        say("Saving patch $slot…")
    }

    fun say(msg: String?) = _state.update { it.copy(message = msg) }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }
    fun logAsText(): String = _state.value.log.joinToString("\n")

    private fun loop(t: AmpTransport) {
        try {
            while (running) {
                while (true) {   // pace writes slightly: the amp drops packets sent back to back
                    val out = outbox.poll() ?: break
                    t.write(out); log(true, out, ""); Thread.sleep(4)
                }
                val p = t.read() ?: continue
                handle(p)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            _state.update { it.copy(connected = false, demo = false, link = "Disconnected", message = "USB: ${e.message ?: e}") }
            log(false, ByteArray(0), "link lost: ${e.message}")
        } finally {
            running = false
            t.close()
        }
    }

    private fun handle(p: ByteArray) {
        val ev = IdCoreProtocol.parse(p)
        if (ev !is Event.TunerReading) log(false, p, describe(ev))
        _state.update { s ->
            when (ev) {
                is Event.FullState -> s.copy(values = ev.values)
                is Event.ControlChanged -> s.copy(values = s.values + (ev.control to ev.value))
                is Event.PatchSelected -> s.copy(slot = ev.slot, manual = false)
                is Event.PatchName -> s.copy(names = s.names + (ev.slot to ev.name))
                is Event.PatchData -> checkStore(s.copy(presets = s.presets + (ev.slot to ev.raw)), ev)
                is Event.Manual -> s.copy(manual = ev.on, slot = if (ev.on) null else s.slot, globals = s.globals + (IdCoreProtocol.Global.MANUAL to if (ev.on) 1 else 0))
                is Event.Tuner -> s.copy(tunerOn = ev.on, globals = s.globals + (IdCoreProtocol.Global.TUNER to if (ev.on) 1 else 0))
                is Event.TunerReading -> s.copy(tunerOn = true, tunerNote = ev.note, tunerCents = ev.cents)
                is Event.GlobalChanged -> {
                    val g = s.copy(globals = s.globals + (ev.id to ev.value))
                    if (ev.id == IdCoreProtocol.Global.PATCH_NUMBER && ev.value in 1..IdCoreProtocol.SLOTS) g.copy(slot = ev.value) else g
                }
                is Event.Unknown -> s
            }
        }
    }

    private fun checkStore(s: AmpState, ev: Event.PatchData): AmpState {
        val (slot, _, want) = pendingStore ?: return s
        if (slot != ev.slot) return s
        pendingStore = null
        val got = IdCoreProtocol.decodeState(ev.raw)
        val bad = want.filter { (c, v) -> c != Control.FX_FOCUS && got[c] != v.coerceIn(c.min, c.max) }.keys
        return s.copy(message = if (bad.isEmpty()) "Saved to patch $slot ✓" else "Patch $slot did not take: ${bad.joinToString { it.name.lowercase() }}")
    }

    private fun describe(ev: Event) = when (ev) {
        is Event.FullState -> "full state"
        is Event.ControlChanged -> "${ev.control.name.lowercase()} = ${ev.value}"
        is Event.PatchSelected -> "patch ${ev.slot} selected"
        is Event.PatchName -> "patch ${ev.slot} name \"${ev.name}\""
        is Event.PatchData -> "patch ${ev.slot} data"
        is Event.Manual -> "manual ${if (ev.on) "on" else "off"}"
        is Event.Tuner -> "tuner ${if (ev.on) "on" else "off"}"
        is Event.TunerReading -> "tuner ${ev.note} ${ev.cents}"
        is Event.GlobalChanged -> "global 0x%02X = %d".format(ev.id, ev.value)
        is Event.Unknown -> "UNKNOWN"
    }

    private fun log(out: Boolean, p: ByteArray, note: String) {
        val line = LogLine(clock.format(Date()), out, if (p.isEmpty()) "--" else IdCoreProtocol.hex(p), note)
        _state.update { it.copy(log = (it.log + line).takeLast(3000)) }
    }
}
