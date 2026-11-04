package pl.kera.usoswidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One class from the USOS timetable. [uid] is the stable USOS id of this meeting. */
data class Lesson(
    val start: Long,
    val end: Long,
    val title: String,
    val detail: String,
    val uid: String = "",
) {
    fun toJson(): JSONObject =
        JSONObject().put("s", start).put("e", end).put("t", title).put("d", detail).put("u", uid)

    companion object {
        fun fromJson(o: JSONObject) =
            Lesson(o.getLong("s"), o.getLong("e"), o.optString("t"), o.optString("d"), o.optString("u"))
    }
}

/** Everything the app remembers, kept in SharedPreferences. */
object Store {
    const val SCHEDULE_AT = "schedule_at"
    const val SCHEDULE_ERR = "schedule_err"
    const val ICS_SOURCE = "ics_source"

    private fun p(c: Context) = c.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun icsUrl(c: Context): String = p(c).getString("ics_url", "") ?: ""
    fun setIcsUrl(c: Context, url: String) = p(c).edit().putString("ics_url", url.trim()).apply()

    fun lessons(c: Context): List<Lesson> =
        readArray(c, "lessons").mapNotNull { runCatching { Lesson.fromJson(it) }.getOrNull() }

    fun saveLessons(c: Context, list: List<Lesson>) = writeArray(c, "lessons", list.map { it.toJson() })

    fun getLong(c: Context, key: String): Long = p(c).getLong(key, 0L)
    fun putLong(c: Context, key: String, value: Long) = p(c).edit().putLong(key, value).apply()
    fun getString(c: Context, key: String, def: String = ""): String = p(c).getString(key, def) ?: def
    fun putString(c: Context, key: String, value: String) = p(c).edit().putString(key, value).apply()

    fun readArray(c: Context, key: String): List<JSONObject> {
        val raw = p(c).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun writeArray(c: Context, key: String, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        p(c).edit().putString(key, arr.toString()).apply()
    }
}
