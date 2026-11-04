package pl.kera.usoswidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle

const val MINUTE = 60_000L

/** Downloads the timetable when it is due, tells about changes, redraws the widgets. */
object Refresh {
    /** Re-download when the copy on the phone is older than this. */
    const val MAX_AGE = 30 * MINUTE

    private val lock = Any()

    /** Blocking (network) — call from a background thread. Returns true when new data was saved. */
    fun run(c: Context, force: Boolean): Boolean {
        val updated = synchronized(lock) { download(c, force) }
        Widgets.updateAll(c)
        return updated
    }

    fun isStale(c: Context): Boolean =
        System.currentTimeMillis() - Store.getLong(c, Store.SCHEDULE_AT) > MAX_AGE

    private fun download(c: Context, force: Boolean): Boolean {
        val url = Store.icsUrl(c)
        if (url.isBlank() || (!force && !isStale(c))) return false
        val now = System.currentTimeMillis()
        try {
            val fresh = Schedule.fetch(url)
            val old = Store.lessons(c)
            val oldHasFuture = old.any { it.end > now }
            if (fresh.isEmpty() && oldHasFuture) {
                // USOS sometimes answers with an empty calendar for a moment; keep what we had.
                Store.putString(c, Store.SCHEDULE_ERR, c.getString(R.string.err_empty))
                return false
            }
            // Same link as last time → tell about moved / cancelled / new classes in the next 7 days.
            if (oldHasFuture && Store.getString(c, Store.ICS_SOURCE) == url) {
                val changes = ScheduleDiff.changes(old, fresh, now)
                if (changes.isNotEmpty()) Notify.scheduleChanged(c, changes)
            }
            Store.saveLessons(c, fresh)
            Store.putString(c, Store.ICS_SOURCE, url)
            Store.putLong(c, Store.SCHEDULE_AT, now)
            Store.putString(c, Store.SCHEDULE_ERR, "")
            return true
        } catch (e: ScheduleException) {
            val msg = if (e.notCalendar) c.getString(R.string.err_not_calendar) else c.getString(R.string.err_http, e.httpCode)
            Store.putString(c, Store.SCHEDULE_ERR, msg)
        } catch (e: Exception) {
            Store.putString(c, Store.SCHEDULE_ERR, c.getString(R.string.no_connection))
        }
        return false
    }
}

/** The background job Android runs for us (about every 15 minutes, later when the phone sleeps). */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val force = params.extras.getBoolean("force", false)
        Thread {
            try {
                Refresh.run(applicationContext, force)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

object Jobs {
    private const val PERIODIC_ID = 1
    private const val ONCE_ID = 2

    /** Makes sure the periodic refresh is scheduled (no-op when it already is). */
    fun ensurePeriodic(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(PERIODIC_ID) != null) return
        val job = JobInfo.Builder(PERIODIC_ID, ComponentName(c, RefreshJob::class.java))
            .setPeriodic(15 * MINUTE)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }
    }

    /** One refresh as soon as there is internet (used from widget broadcasts). */
    fun refreshSoon(c: Context, force: Boolean) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        val extras = PersistableBundle().apply { putBoolean("force", force) }
        val job = JobInfo.Builder(ONCE_ID, ComponentName(c, RefreshJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(extras)
            .build()
        runCatching { js.schedule(job) }
    }
}

/** One difference between two downloads of the timetable. */
data class Change(val kind: Kind, val lesson: Lesson, val old: Lesson? = null) {
    enum class Kind { ADDED, MOVED, DETAIL, CANCELLED }
}

/** What changed in the coming days between two downloads of the timetable. */
object ScheduleDiff {
    fun changes(old: List<Lesson>, fresh: List<Lesson>, now: Long, days: Int = 7): List<Change> {
        val until = now + days * 24 * 60 * MINUTE
        fun inWindow(l: Lesson) = l.uid.isNotEmpty() && l.end > now && l.start < until
        val oldById = old.filter { it.uid.isNotEmpty() }.associateBy { it.uid }
        val freshIds = fresh.map { it.uid }.toHashSet()
        val out = ArrayList<Change>()
        for (n in fresh.filter(::inWindow)) {
            val o = oldById[n.uid]
            when {
                o == null -> out.add(Change(Change.Kind.ADDED, n))
                o.start != n.start || o.end != n.end -> out.add(Change(Change.Kind.MOVED, n, o))
                o.detail != n.detail -> out.add(Change(Change.Kind.DETAIL, n, o))
            }
        }
        for (o in old.filter(::inWindow)) {
            if (o.uid !in freshIds) out.add(Change(Change.Kind.CANCELLED, o))
        }
        return out
    }
}

object Notify {
    private const val CHANNEL_CHANGES = "schedule_changes"

    fun describe(c: Context, ch: Change): String {
        val l = ch.lesson
        val at = "${Fmt.dayShort(l.start)} ${Fmt.hm(l.start)}"
        return when (ch.kind) {
            Change.Kind.ADDED -> c.getString(R.string.change_new, l.title, at)
            Change.Kind.CANCELLED -> c.getString(R.string.change_cancelled, l.title, at)
            Change.Kind.MOVED -> {
                val o = ch.old ?: l
                c.getString(R.string.change_moved, l.title, "${Fmt.dayShort(o.start)} ${Fmt.hm(o.start)}", at)
            }
            Change.Kind.DETAIL ->
                c.getString(R.string.change_detail, l.title, Fmt.dayShort(l.start), ch.old?.detail.orEmpty(), l.detail)
        }
    }

    fun scheduleChanged(c: Context, changes: List<Change>) {
        val lines = changes.map { describe(c, it) }
        var text = lines.take(6).joinToString("\n")
        if (lines.size > 6) text += "\n" + c.getString(R.string.changes_more, lines.size - 6)
        post(c, 2, c.getString(R.string.changes_title, lines.size), text)
    }

    private fun post(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHANGES, c.getString(R.string.ch_changes), NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            c, id, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
