package pl.kera.usoswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class IcsTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(warsaw).toInstant().toEpochMilli()

    private val lessons by lazy {
        Ics.parse(javaClass.classLoader!!.getResource("plan.ics")!!.readText())
    }

    @Test
    fun parsesAllEventsInOrder() {
        assertEquals(7, lessons.size)
        assertTrue(lessons.zipWithNext().all { (a, b) -> a.start <= b.start })
    }

    @Test
    fun turnsUsosTextIntoShortLabels() {
        val first = lessons[0]
        // "W - Sztuczna Inteligencja\; SPEC: …" is folded over two lines in the file.
        assertEquals("Sztuczna Inteligencja", first.title)
        assertEquals("W, gr. 1 (114)", first.detail)
        assertEquals("Lab, gr. 1 (127)", lessons[1].detail) // "(Comp. Room)" removed
        assertEquals("C, gr. 4 (125)", lessons[5].detail)
        assertEquals("sm-1199330@usosapps.vistula.edu.pl", first.uid)
    }

    @Test
    fun readsWarsawLocalTime() {
        assertEquals(at(2026, 11, 2, 8, 0), lessons[0].start)
        assertEquals(at(2026, 11, 2, 9, 55), lessons[0].end)
    }

    @Test
    fun skipsCancelledEvents() {
        val ics = """
            BEGIN:VCALENDAR
            X-WR-TIMEZONE:Europe/Warsaw
            BEGIN:VEVENT
            SUMMARY:W - Test
            DTSTART;VALUE=DATE-TIME:20261102T080000
            DTEND;VALUE=DATE-TIME:20261102T095500
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Lab - Test 2
            DTSTART:20261102T070000Z
            DTEND:20261102T083000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val list = Ics.parse(ics)
        assertEquals(1, list.size)
        assertEquals("Test 2", list[0].title)
        assertEquals(at(2026, 11, 2, 8, 0), list[0].start) // 07:00 UTC = 08:00 in Warsaw (winter)
    }

    @Test
    fun understandsEnglishAndOtherUniversities() {
        val ics = listOf(
            // USOSweb in English (lang=en)
            "SUMMARY:W - Artificial Intelligence\\; SPEC: Engineering of Artificial Intelligence",
            "DESCRIPTION:Room: 127 (Comp. Room)\\nVistula University\\n\\nhttp://usosweb.x/k?gr_nr=1&zaj_cyk_id=1\\n",
            // another university: long Polish class type, no group in the link
            "SUMMARY:Wykład - Analiza matematyczna I",
            "DESCRIPTION:Sala: 2.14\\nWydział Matematyki\\n",
            // a course name that itself contains " - "
            "SUMMARY:Analiza danych - projekt zespołowy",
        ).let { lines ->
            """
            BEGIN:VCALENDAR
            X-WR-TIMEZONE:Europe/Warsaw
            BEGIN:VEVENT
            ${lines[0]}
            ${lines[1]}
            DTSTART:20261102T080000
            END:VEVENT
            BEGIN:VEVENT
            ${lines[2]}
            ${lines[3]}
            DTSTART:20261102T100000
            END:VEVENT
            BEGIN:VEVENT
            ${lines[4]}
            DTSTART:20261102T120000
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        }
        val l = Ics.parse(ics)
        assertEquals("Artificial Intelligence", l[0].title)
        assertEquals("W, gr. 1 (127)", l[0].detail)
        assertEquals("Analiza matematyczna I", l[1].title)
        assertEquals("Wykład (2.14)", l[1].detail)
        assertEquals("Analiza danych - projekt zespołowy", l[2].title)
        assertEquals("", l[2].detail)
    }

    @Test
    fun normalizesLinks() {
        assertEquals("https://usosapps.vistula.edu.pl/x", Schedule.normalize("webcal://usosapps.vistula.edu.pl/x"))
        assertEquals("https://usosapps.vistula.edu.pl/x", Schedule.normalize(" http://usosapps.vistula.edu.pl/x "))
        assertEquals("http://10.0.2.2:8000/plan.ics", Schedule.normalize("http://10.0.2.2:8000/plan.ics"))
    }

    @Test
    fun findsLinkInSharedText() {
        assertEquals(
            "https://usosapps.vistula.edu.pl/services/tt/upcoming_ical?lang=pl&user_id=1&key=abc",
            Schedule.extractLink("Mój plan: https://usosapps.vistula.edu.pl/services/tt/upcoming_ical?lang=pl&user_id=1&key=abc 👍"),
        )
        assertNull(Schedule.extractLink("no link here"))
    }
}
