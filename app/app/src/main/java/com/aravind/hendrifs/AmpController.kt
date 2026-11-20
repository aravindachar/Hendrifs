package com.aravind.hendrifs

import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import com.aravind.hendrifs.protocol.IdCoreProtocol.Event
import com.aravind.hendrifs.protocol.IdCoreProtocol.Global
import com.aravind.hendrifs.usb.AmpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

data class LogLine(val time: String, val outgoing: Boolean, val hex: String, val note: String) {
    override fun toString() = "$time ${if (outgoing) "OUT" else " IN"} $hex${if (note.isNotEmpty()) "   # $note" else ""}"
}

data class AmpState(
    val connected: Boolean = false,
    val demo: Boolean = false,
    val link: String = "No amp",
    val version: String = "",
    val ready: Boolean = false,                       // the amp has sent its live sound
    val values: Map<Control, Int> = emptyMap(),
    val globals: Map<Int, Int> = emptyMap(),
    val slot: Int? = null,
    val names: Map<Int, String> = emptyMap(),
    val patchesRead: Int = 0,
    val tunerOn: Boolean = false,
    val message: String? = null,
)

/** Tuner readings change ~10-20 times a second, so they travel separately from the rest of the state. */
data class TunerState(val on: Boolean = false, val note: String = "", val cents: Int = 0, val hz: Double = 0.0, val source: String = "")

/** Owns the worker thread: every USB read and write happens there, in order. */
class AmpController {
    private val _state = MutableStateFlow(AmpState())
    val state: StateFlow<AmpState> = _state
    private val _tuner = MutableStateFlow(TunerState())
    val tuner: StateFlow<TunerState> = _tuner

    /** Called on the worker thread when the USB link dies (not when we disconnect on purpose). */
    @Volatile var onLinkLost: (() -> Unit)? = null

    // Ordered commands (patch select, store, handshake...).
    private val outbox = LinkedBlockingQueue<ByteArray>()
    // Knob moves: only the newest value per control is kept, so a fast drag never floods the amp.
    private val pendingControls = ConcurrentHashMap<Control, Int>()
    private val lastSent = ConcurrentHashMap<Control, Long>()
    // When the app last changed each parameter id; the amp's echoes of older values are ignored meanwhile.
    private val localUntil = LongArray(IdCoreProtocol.TABLE)

    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var generation = 0

    // Live tables, indexed by parameter / setting id. Touched only under `lock`.
    private val lock = Any()
    private val live = IntArray(IdCoreProtocol.TABLE)
    private val globals = IntArray(IdCoreProtocol.TABLE)
    private val patches = HashMap<Int, IntArray>()
    private var pendingStore: Pair<Int, IntArray>? = null

    // USB log: a ring buffer outside the UI state, read only when the console asks for it.
    private val logBuf = ArrayDeque<LogLine>()
    private var lastInHex = ""
    private var repeats = 0
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun connect(transport: AmpTransport, demo: Boolean) {
        disconnect()
        outbox.clear(); pendingControls.clear()
        synchronized(lock) { live.fill(0); globals.fill(0); patches.clear(); pendingStore = null; localUntil.fill(0) }
        running = true
        val gen = ++generation
        _state.value = AmpState(connected = true, demo = demo, link = transport.description)
        _tuner.value = TunerState()
        log(true, ByteArray(0), "connected: ${transport.description}")
        worker = Thread({ loop(transport, gen) }, "amp-usb").also { it.start() }
        send(IdCoreProtocol.requestState())
        for (s in 1..IdCoreProtocol.SLOTS) { send(IdCoreProtocol.requestPatchName(s)); send(IdCoreProtocol.requestPatch(s)) }
    }

    fun disconnect() {
        running = false
        generation++
        worker?.let { it.interrupt(); if (it !== Thread.currentThread()) it.join(500) }
        worker = null
        _state.update { it.copy(connected = false, demo = false, ready = false, link = "No amp", tunerOn = false) }
        _tuner.value = TunerState()
    }

    val isConnected get() = running

    fun send(packet: ByteArray) { if (running) outbox.put(packet) }

    /** Changes a control live. The screen keeps our value; the packet goes out at most every ~25 ms per control. */
    fun set(c: Control, value: Int) {
        val v = value.coerceIn(c.min, c.max)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            live[c.id] = v and 0xFF; localUntil[c.id] = now + ECHO_GUARD_MS
            if (c.twoBytes) { live[c.id + 1] = v shr 8; localUntil[c.id + 1] = now + ECHO_GUARD_MS }
        }
        publishValues()
        if (running) pendingControls[c] = v
    }

    fun setGlobal(id: Int, value: Int) {
        runCatching { IdCoreProtocol.setGlobal(id, value) }
            .onSuccess { pkt -> synchronized(lock) { globals[id] = value and 0xFF }; publishGlobals(); send(pkt) }
            .onFailure { say("That setting can't be changed from the app.") }
    }

    fun setTuner(on: Boolean) {
        send(IdCoreProtocol.tuner(on))
        synchronized(lock) { globals[Global.TUNER] = if (on) 1 else 0 }
        _state.update { it.copy(tunerOn = on) }
        _tuner.update { if (on) it.copy(on = true) else TunerState() }
    }

    // When each tuner source last heard an actual note. Whichever is hearing one is shown; the phone wins a tie
    // because it is finer. (While tuning, the amp mutes its output, which can include its USB audio.)
    @Volatile private var audioNoteAt = 0L
    @Volatile private var ampNoteAt = 0L

    /** Phone-side pitch readings (from the amp's USB audio). */
    fun audioTuner(note: String, cents: Int, hz: Double) {
        val now = System.currentTimeMillis()
        if (note.isNotEmpty()) audioNoteAt = now
        else if (now - ampNoteAt < TUNER_HOLD_MS) return          // the amp hears the string; keep its reading
        if (!_state.value.tunerOn) return
        _tuner.value = TunerState(true, note, cents, hz, "audio")
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
        _state.update { it.copy(slot = slot) }
        send(IdCoreProtocol.selectPatch(slot))
    }

    fun refresh() {
        send(IdCoreProtocol.requestState())
        for (s in 1..IdCoreProtocol.SLOTS) { send(IdCoreProtocol.requestPatchName(s)); send(IdCoreProtocol.requestPatch(s)) }
    }

    /** Applies a saved sound live in one report, like Architect. Controls it doesn't mention keep their value. */
    fun apply(values: Map<Control, Int>) {
        // One ordinary knob command per control (voice first), exactly like turning the knobs. Volume is never
        // sent: on the V4 it is part of the sound, and sending 0 (as a whole-sound block must) silences the amp.
        val wanted = values.filterKeys { it != Control.VOLUME }
        val order = wanted.keys.sortedBy { if (it == Control.VOICE) -1 else it.id }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            IdCoreProtocol.encode(wanted, live).copyInto(live)
            order.forEach { c -> localUntil[c.id] = now + ECHO_GUARD_MS; if (c.twoBytes) localUntil[c.id + 1] = now + ECHO_GUARD_MS }
        }
        pendingControls.clear()
        publishValues()
        order.forEach { c -> send(IdCoreProtocol.setControl(c, wanted.getValue(c))) }
    }

    /** Stores the current live sound in [slot] under [name], then reads it back to confirm. */
    fun storeCurrent(slot: Int, name: String) {
        val t = synchronized(lock) {
            live.copyOf().also { t ->
                // The live reports always carry volume 0, so keep the level the patch already had (never store silence).
                val stored = patches[slot]?.get(Control.VOLUME.id) ?: 0
                t[Control.VOLUME.id] = when { stored > 0 -> stored; live[Control.VOLUME.id] > 0 -> live[Control.VOLUME.id]; else -> DEFAULT_VOLUME }
            }
        }
        store(slot, name, t)
    }

    /** Writes a backed-up patch (a parameter table) back to [slot]. */
    fun restore(slot: Int, name: String, table: IntArray) =
        store(slot, name, table.copyOf().also { if (it[Control.VOLUME.id] == 0) it[Control.VOLUME.id] = DEFAULT_VOLUME })

    private fun store(slot: Int, name: String, t: IntArray) {
        synchronized(lock) { pendingStore = slot to t.copyOf() }
        IdCoreProtocol.storePackets(slot, name, t).forEach(::send)
        send(IdCoreProtocol.requestPatchName(slot)); send(IdCoreProtocol.requestPatch(slot))
        say("Saving patch $slot…")
    }

    fun patchTable(slot: Int): IntArray? = synchronized(lock) { patches[slot]?.copyOf() }

    fun say(msg: String?) = _state.update { it.copy(message = msg) }

    fun clearLog() = synchronized(logBuf) { logBuf.clear() }
    fun logAsText(): String = synchronized(logBuf) { logBuf.joinToString("\n") }

    // ------------------------------------------------------------------ worker

    private fun loop(t: AmpTransport, gen: Int) {
        var handshakeAt = System.currentTimeMillis()
        var handshakes = 1
        try {
            while (running && gen == generation) {
                // One write per turn, then a read, so incoming reports never pile up behind a knob drag.
                val out = outbox.poll() ?: nextControlPacket()
                if (out != null) { t.write(out); log(true, out, ""); Thread.sleep(WRITE_GAP_MS) }
                val p = t.read()
                if (p != null) handle(p)
                if (!_state.value.ready && System.currentTimeMillis() - handshakeAt > 1200) {
                    if (handshakes < 4) {
                        handshakes++; handshakeAt = System.currentTimeMillis()
                        log(true, ByteArray(0), "no answer, asking again ($handshakes)")
                        t.write(IdCoreProtocol.requestState())
                    } else if (handshakes == 4) {
                        handshakes++
                        say("The amp isn't answering. Switch it off and on, then tap the status lamp.")
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (gen == generation) {
                running = false
                log(false, ByteArray(0), "link lost: ${e.message}")
                _state.update { it.copy(connected = false, demo = false, ready = false, link = "Reconnecting…", tunerOn = false) }
                _tuner.value = TunerState()
                onLinkLost?.invoke()
            }
        } finally {
            t.close()
        }
    }

    private fun nextControlPacket(): ByteArray? {
        val now = System.currentTimeMillis()
        for ((c, v) in pendingControls) {
            if (now - (lastSent[c] ?: 0L) < CONTROL_GAP_MS) continue
            if (pendingControls.remove(c, v)) {
                lastSent[c] = now
                synchronized(lock) {   // keep guarding while the user is still moving it
                    localUntil[c.id] = now + ECHO_GUARD_MS
                    if (c.twoBytes) localUntil[c.id + 1] = now + ECHO_GUARD_MS
                }
                return IdCoreProtocol.setControl(c, v)
            }
        }
        return null
    }

    private fun handle(p: ByteArray) {
        val ev = IdCoreProtocol.parse(p)
        if (ev !is Event.TunerReading) logIn(p, ev)
        when (ev) {
            is Event.Params -> {
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(lock) {
                    ev.bytes.forEachIndexed { i, v ->
                        val id = ev.first + i
                        if (id == Control.VOLUME.id && ev.whole && v == 0) return@forEachIndexed   // volume isn't part of a sound
                        if (!ev.whole && now < localUntil[id]) return@forEachIndexed               // our own echo
                        if (live[id] != v) { live[id] = v; changed = true }
                    }
                    if (ev.whole) localUntil.fill(0)
                }
                if (changed) publishValues()
                if (!_state.value.ready) _state.update { it.copy(ready = true) }
            }
            is Event.Globals -> {
                var changed = false
                synchronized(lock) { ev.bytes.forEachIndexed { i, v -> val id = ev.first + i; if (globals[id] != v) { globals[id] = v; changed = true } } }
                val tunerTouched = Global.TUNER in ev.first until ev.first + ev.bytes.size
                if (tunerTouched) {
                    val on = synchronized(lock) { globals[Global.TUNER] } == 1
                    if (on != _state.value.tunerOn) {
                        _state.update { it.copy(tunerOn = on) }
                        _tuner.update { if (on) it.copy(on = true) else TunerState() }
                    }
                }
                if (changed) publishGlobals()
            }
            is Event.PatchSelected -> {
                synchronized(lock) { localUntil.fill(0) }
                _state.update { it.copy(slot = ev.slot.takeIf { s -> s in 1..IdCoreProtocol.SLOTS }) }
            }
            is Event.PatchName -> _state.update { it.copy(names = it.names + (ev.slot to ev.name)) }
            is Event.PatchData -> {
                val t = IntArray(IdCoreProtocol.TABLE).also { tb -> ev.bytes.forEachIndexed { i, v -> tb[i + 1] = v } }
                val (count, verdict) = synchronized(lock) {
                    patches[ev.slot] = t
                    patches.size to checkStore(ev.slot, t)
                }
                _state.update { it.copy(patchesRead = count, message = verdict ?: it.message) }
            }
            is Event.Version -> _state.update { it.copy(version = ev.text) }
            is Event.TunerReading -> {
                val now = System.currentTimeMillis()
                if (!_state.value.tunerOn) _state.update { it.copy(tunerOn = true) }
                if (now - audioNoteAt < TUNER_HOLD_MS) return             // the phone hears a note: finer, keep it
                if (ev.note != 0) ampNoteAt = now
                _tuner.value = TunerState(true, if (ev.note == 0) "" else ev.name, ev.cents, 0.0, "amp")
            }
            is Event.Unknown -> {}
        }
    }

    /** Compares a freshly read patch with what we just stored; returns a message, or null if no store is pending. */
    private fun checkStore(slot: Int, got: IntArray): String? {
        val (want, t) = pendingStore ?: return null
        if (want != slot) return null
        pendingStore = null
        val bad = (1..IdCoreProtocol.SOUND_LEN).filter { it != Control.VOLUME.id && got[it] != t[it] }
        return if (bad.isEmpty()) "Saved to patch $slot ✓" else "Patch $slot was not saved correctly. Try again."
    }

    private fun publishValues() {
        val v = synchronized(lock) { IdCoreProtocol.decode(live) }
        _state.update { it.copy(values = v) }
    }

    private fun publishGlobals() {
        val g = synchronized(lock) { (1 until IdCoreProtocol.TABLE).associateWith { globals[it] } }
        _state.update { it.copy(globals = g) }
    }

    private fun describe(ev: Event) = when (ev) {
        is Event.Params -> if (ev.whole) "whole sound" else ev.bytes.mapIndexed { i, v ->
            (Control.byId(ev.first + i)?.name?.lowercase() ?: "0x%02X".format(ev.first + i)) + "=" + v }.joinToString(" ")
        is Event.Globals -> if (ev.bytes.size > 4) "all settings" else ev.bytes.mapIndexed { i, v -> "set 0x%02X=%d".format(ev.first + i, v) }.joinToString(" ")
        is Event.PatchSelected -> "patch ${ev.slot} selected"
        is Event.PatchName -> "patch ${ev.slot} name \"${ev.name}\""
        is Event.PatchData -> "patch ${ev.slot} data"
        is Event.Version -> "firmware ${ev.text}"
        is Event.TunerReading -> "tuner ${ev.name} ${ev.cents}"
        is Event.Unknown -> "not decoded"
    }

    private fun logIn(p: ByteArray, ev: Event) {
        val h = IdCoreProtocol.hex(p)
        synchronized(logBuf) {
            if (h == lastInHex) { repeats++; return }   // the amp repeats knob reports every ~10 ms
            if (repeats > 0) { add(LogLine(clock.format(Date()), false, "(previous ×$repeats)", "")); repeats = 0 }
            lastInHex = h
        }
        log(false, p, describe(ev))
    }

    private fun log(out: Boolean, p: ByteArray, note: String) =
        add(LogLine(clock.format(Date()), out, if (p.isEmpty()) "--" else IdCoreProtocol.hex(p), note))

    private fun add(line: LogLine) = synchronized(logBuf) {
        logBuf.addLast(line)
        while (logBuf.size > 3000) logBuf.removeFirst()
    }

    companion object {
        const val ECHO_GUARD_MS = 500L
        const val TUNER_HOLD_MS = 400L
        /** Patch volume used when a patch has none (about 5 on the knob). */
        const val DEFAULT_VOLUME = 0x41
        const val CONTROL_GAP_MS = 25L
        const val WRITE_GAP_MS = 3L
    }
}
