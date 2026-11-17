package com.aravind.hendrifs

import com.aravind.hendrifs.protocol.IdCoreProtocol
import com.aravind.hendrifs.protocol.IdCoreProtocol.Control
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved sounds on the tablet ("cassettes"). A patch keeps control values; a backup keeps the six
 * raw patch packets and names exactly as the amp sent them, so it can be written back unchanged.
 */
class LibraryStore(dir: File) {
    private val file = File(dir, "library.json")

    data class Entry(val id: Long, val name: String, val genre: String, val kind: String,
                     val values: Map<Control, Int>, val backup: List<Pair<String, ByteArray>> = emptyList())

    fun all(): List<Entry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun get(id: Long) = all().firstOrNull { it.id == id }

    fun savePatch(name: String, genre: String, values: Map<Control, Int>): Entry =
        add(Entry(System.currentTimeMillis(), name.take(40), genre, "patch", values.filterKeys { it != Control.VOLUME }))

    fun saveBackup(name: String, slots: List<Pair<String, ByteArray>>): Entry =
        add(Entry(System.currentTimeMillis(), name, "Backup", "backup", emptyMap(), slots))

    fun delete(id: Long) = write(all().filter { it.id != id })

    private fun add(e: Entry): Entry { write(all() + e); return e }

    private fun write(list: List<Entry>) {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(JSONArray(list.map { toJson(it) }).toString())
        tmp.renameTo(file)   // replace in one step so a crash never leaves half a file
    }

    fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id); put("name", e.name); put("genre", e.genre); put("kind", e.kind)
        put("values", JSONObject().apply { e.values.forEach { (c, v) -> put(c.name, v) } })
        put("voice", e.values[Control.VOICE]?.let { IdCoreProtocol.VOICES.getOrNull(it) } ?: "")
        put("backup", JSONArray(e.backup.map { (n, raw) -> JSONObject().put("name", n).put("raw", IdCoreProtocol.hex(raw, 64)) }))
    }

    private fun fromJson(o: JSONObject): Entry {
        val vals = o.optJSONObject("values")
        val values = buildMap { vals?.keys()?.forEach { k -> Control.byName(k)?.let { put(it, vals.getInt(k)) } } }
        val bk = o.optJSONArray("backup")
        val backup = (0 until (bk?.length() ?: 0)).map { i ->
            val b = bk!!.getJSONObject(i); b.getString("name") to IdCoreProtocol.parseHex(b.getString("raw"))
        }
        return Entry(o.getLong("id"), o.getString("name"), o.optString("genre", "Other"), o.optString("kind", "patch"), values, backup)
    }
}
