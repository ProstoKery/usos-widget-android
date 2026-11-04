package pl.kera.usoswidget

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Why a download failed; turned into a translated message by the caller. */
class ScheduleException(val httpCode: Int = 0, val notCalendar: Boolean = false) : IOException()

/** Downloads the USOS calendar link ("Plan zajęć → Eksportuj"). */
object Schedule {
    /** webcal:// and http:// links are fetched over https (except the emulator's test server). */
    fun normalize(url: String): String {
        val u = url.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")
        return if (u.startsWith("http://10.0.2.2", ignoreCase = true)) u
        else u.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    }

    /** Finds the first web or webcal link in shared text. */
    fun extractLink(text: String?): String? =
        text?.let { Regex("""(?:https?|webcal)://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(it)?.value }

    fun fetch(url: String): List<Lesson> {
        val conn = URL(normalize(url)).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "UsosWidget/1.0 (Android)")
        try {
            val code = conn.responseCode
            if (code != 200) throw ScheduleException(httpCode = code)
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!text.contains("BEGIN:VCALENDAR")) throw ScheduleException(notCalendar = true)
            return Ics.parse(text)
        } finally {
            conn.disconnect()
        }
    }
}

/** Minimal iCalendar reader, enough for the USOS export. */
object Ics {
    private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    fun parse(text: String): List<Lesson> {
        var calendarZone = WARSAW
        val out = ArrayList<Lesson>()
        var event: HashMap<String, Prop>? = null
        for (line in unfold(text)) {
            when (line) {
                "BEGIN:VEVENT" -> event = HashMap()
                "END:VEVENT" -> {
                    val ev = event
                    if (ev != null) toLesson(ev, calendarZone)?.let { out.add(it) }
                    event = null
                }
                else -> {
                    val prop = parseLine(line) ?: continue
                    val ev = event
                    if (ev != null) {
                        if (!ev.containsKey(prop.name)) ev[prop.name] = prop
                    } else if (prop.name == "X-WR-TIMEZONE") {
                        calendarZone = zoneOrNull(prop.value) ?: WARSAW
                    }
                }
            }
        }
        return out.sortedBy { it.start }
    }

    /**
     * "W - Sztuczna Inteligencja; SPEC: …" + "Sala: 114 … gr_nr=1 …" (or "Room: 114" when USOSweb is in English)
     * becomes ("Sztuczna Inteligencja", "W, gr. 1 (114)").
     */
    fun describe(summary: String, description: String): Pair<String, String> {
        var type = ""
        var name = summary
        // "W - …", "Lab - …", "Wykład - …": a short one-word class type before " - "
        val dash = summary.indexOf(" - ")
        if (dash in 1..8 && !summary.substring(0, dash).contains(' ')) {
            type = summary.substring(0, dash).trim()
            name = summary.substring(dash + 3)
        }
        name = name.substringBefore("; SPEC:").trim()
        val room = Regex("""(?:Sala|Room):\s*([^\n]+)""").find(description)?.groupValues?.get(1)
            ?.replace(Regex("""\s*\(Comp\. Room\)""", RegexOption.IGNORE_CASE), "")
            ?.trim().orEmpty()
        val group = Regex("""gr_nr=(\d+)""").find(description)?.groupValues?.get(1).orEmpty()

        val parts = ArrayList<String>()
        if (type.isNotEmpty()) parts.add(type)
        if (group.isNotEmpty()) parts.add("gr. $group")
        var detail = parts.joinToString(", ")
        if (room.isNotEmpty()) detail = if (detail.isEmpty()) room else "$detail ($room)"
        return Pair(name.ifEmpty { summary }, detail)
    }

    private fun toLesson(ev: Map<String, Prop>, zone: ZoneId): Lesson? {
        if (ev["STATUS"]?.value?.trim().equals("CANCELLED", ignoreCase = true)) return null
        val start = time(ev["DTSTART"], zone) ?: return null
        val end = time(ev["DTEND"], zone) ?: (start + 90 * 60_000L)
        val summary = unescape(ev["SUMMARY"]?.value.orEmpty()).trim()
        val description = unescape(ev["DESCRIPTION"]?.value.orEmpty())
        val (title, detail) = describe(summary, description)
        val uid = ev["UID"]?.value?.trim().orEmpty()
        return Lesson(start, end, title, detail, uid)
    }

    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\r\n", "\n")) {
            if (raw.isEmpty()) continue
            if ((raw[0] == ' ' || raw[0] == '\t') && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + raw.substring(1)
            } else {
                out.add(raw)
            }
        }
        return out
    }

    private fun parseLine(line: String): Prop? {
        var inQuotes = false
        var colon = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == ':' && !inQuotes) {
                colon = i
                break
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = HashMap<String, String>()
        for (p in head.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
        }
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                sb.append(if (next == 'n' || next == 'N') '\n' else next)
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun time(prop: Prop?, calendarZone: ZoneId): Long? {
        if (prop == null) return null
        val v = prop.value.trim()
        return try {
            if (prop.params["VALUE"] == "DATE" || v.length == 8) {
                LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(calendarZone).toInstant().toEpochMilli()
            } else {
                val tzid = prop.params["TZID"]
                val zone: ZoneId = when {
                    v.endsWith("Z") -> ZoneOffset.UTC
                    tzid != null -> zoneOrNull(tzid) ?: calendarZone
                    else -> calendarZone
                }
                LocalDateTime.parse(v.removeSuffix("Z").take(15), DATE_TIME)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun zoneOrNull(id: String): ZoneId? = try {
        ZoneId.of(id.trim())
    } catch (e: Exception) {
        null
    }
}
