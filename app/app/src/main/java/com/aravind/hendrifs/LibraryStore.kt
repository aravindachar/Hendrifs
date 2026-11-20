package com.aravind.hendrifs

import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved sounds on the device ("cassettes"). A patch keeps control values; a backup keeps the six patch
 * parameter tables and names exactly as the amp sent them, so they can be written back unchanged.
 */
class LibraryStore(dir: File) {
    private val file = File(dir, "library.json")
    private var cache: List<Entry>? = null

    data class Entry(
        val id: Long, val name: String, val genre: String, val kind: String,
        val values: Map<Control, Int>,
        val backup: List<Pair<String, IntArray>> = emptyList(),
        val artist: String = "", val source: String = "user",
    )

    @Synchronized fun all(): List<Entry> = cache ?: runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList()).also { cache = it }

    fun get(id: Long) = all().firstOrNull { it.id == id }

    fun savePatch(name: String, genre: String, values: Map<Control, Int>, artist: String = "", source: String = "user"): Entry =
        add(Entry(newId(), name.take(40), genre, "patch", values.filterKeys { it != Control.VOLUME }, artist = artist.take(40), source = source))

    fun saveBackup(name: String, slots: List<Pair<String, IntArray>>): Entry =
        add(Entry(newId(), name, "Backup", "backup", emptyMap(), slots))

    @Synchronized fun delete(id: Long) = write(all().filter { it.id != id })

    @Synchronized private fun add(e: Entry): Entry { write(all() + e); return e }

    private var lastId = 0L
    @Synchronized private fun newId(): Long { lastId = maxOf(System.currentTimeMillis(), lastId + 1); return lastId }

    private fun write(list: List<Entry>) {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(JSONArray(list.map { toJson(it) }).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cache = list
    }

    fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id); put("name", e.name); put("genre", e.genre); put("kind", e.kind)
        put("artist", e.artist); put("source", e.source)
        put("values", JSONObject().apply { e.values.forEach { (c, v) -> put(c.name, v) } })
        put("voice", e.values[Control.VOICE]?.let { IdCoreProtocol.VOICES.getOrNull(it) } ?: "")
        put("backup", JSONArray(e.backup.map { (n, t) ->
            JSONObject().put("name", n).put("table", (1..IdCoreProtocol.SOUND_LEN).joinToString(" ") { "%02X".format(t[it]) })
        }))
    }

    private fun fromJson(o: JSONObject): Entry {
        val vals = o.optJSONObject("values")
        val values = buildMap { vals?.keys()?.forEach { k -> Control.byName(k)?.let { put(it, vals.getInt(k)) } } }
        val bk = o.optJSONArray("backup")
        val backup = (0 until (bk?.length() ?: 0)).map { i ->
            val b = bk!!.getJSONObject(i)
            val t = IntArray(IdCoreProtocol.TABLE)
            if (b.has("table")) {
                b.getString("table").split(" ").forEachIndexed { j, h -> t[j + 1] = h.toInt(16) }
            } else {
                // Version 0.2 kept the whole "02 05 <slot> 2A ..." report: parameter id N sat at byte N + 3.
                val raw = IdCoreProtocol.parseHex(b.getString("raw"))
                for (id in 1..IdCoreProtocol.SOUND_LEN) t[id] = raw[id + 3].toInt() and 0xFF
            }
            b.getString("name") to t
        }
        return Entry(o.getLong("id"), o.getString("name"), o.optString("genre", "Other"), o.optString("kind", "patch"),
            values, backup, o.optString("artist", ""), o.optString("source", "user"))
    }
}
