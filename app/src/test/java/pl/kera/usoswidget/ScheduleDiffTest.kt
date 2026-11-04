package pl.kera.usoswidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleDiffTest {
    private val lessons by lazy { Ics.parse(javaClass.classLoader!!.getResource("plan.ics")!!.readText()) }
    private val now = LocalDateTime.of(2026, 11, 2, 7, 0).atZone(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
    private val hour = 60 * MINUTE

    @Test
    fun noChangesNoAlerts() {
        assertTrue(ScheduleDiff.changes(lessons, lessons, now).isEmpty())
    }

    @Test
    fun detectsMovedCancelledAndRoomChange() {
        val fresh = lessons
            .map { if (it.uid.startsWith("sm-1199689")) it.copy(start = it.start + hour, end = it.end + hour) else it }
            .filterNot { it.uid.startsWith("sm-1196431") }
            .map { if (it.uid.startsWith("sm-1199701")) it.copy(detail = "Lab, gr. 1 (A3)") else it }
        val kinds = ScheduleDiff.changes(lessons, fresh, now).map { it.kind }
        assertEquals(listOf(Change.Kind.MOVED, Change.Kind.DETAIL, Change.Kind.CANCELLED), kinds)
    }

    @Test
    fun detectsNewClass() {
        val extra = lessons[0].copy(uid = "sm-new@usosapps", start = lessons[0].start + 2 * hour, end = lessons[0].end + 2 * hour)
        val changes = ScheduleDiff.changes(lessons, lessons + extra, now)
        assertEquals(listOf(Change.Kind.ADDED), changes.map { it.kind })
    }

    @Test
    fun ignoresClassesBeyondSevenDays() {
        // The January class is far away: a new week scrolling into the calendar is not a "change".
        assertTrue(ScheduleDiff.changes(lessons.dropLast(1), lessons, now).isEmpty())
    }

    @Test
    fun ignoresClassesThatAlreadyEnded() {
        val later = lessons[0].end + MINUTE
        val fresh = lessons.drop(1)
        assertTrue(ScheduleDiff.changes(lessons, fresh, later).isEmpty())
    }
}
