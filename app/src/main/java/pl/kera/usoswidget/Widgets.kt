package pl.kera.usoswidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Date and time formatting shared by the widgets and notifications; day names follow the phone language. */
object Fmt {
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

    private fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun dow(d: LocalDate): String =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).trimEnd('.')
    private fun dm(d: LocalDate): String = "${d.dayOfMonth}.${"%02d".format(d.monthValue)}"

    fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)
    fun sameDay(a: Long, b: Long): Boolean = date(a) == date(b)

    /** "wt 3.11" / "Tue 3.11" / "вт 3.11" */
    fun dayShort(ms: Long): String {
        val d = date(ms)
        return "${dow(d)} ${dm(d)}"
    }

    /** "Today · Mon, 2.11" */
    fun dayHeader(c: Context, ms: Long, now: Long): String {
        val d = date(ms)
        val today = date(now)
        val base = "${dow(d).replaceFirstChar { it.titlecase(Locale.getDefault()) }}, ${dm(d)}"
        return when (d) {
            today -> "${c.getString(R.string.today)} · $base"
            today.plusDays(1) -> "${c.getString(R.string.tomorrow)} · $base"
            else -> base
        }
    }

    /** Short "when" text for the next-class widget: "until 9:55", "8:00", "tomorrow 8:00", "Wed 4.11 10:10". */
    fun whenText(c: Context, l: Lesson, now: Long): String {
        if (l.start <= now) return c.getString(R.string.until, hm(l.end))
        val d = date(l.start)
        val today = date(now)
        return when (d) {
            today -> hm(l.start)
            today.plusDays(1) -> c.getString(R.string.tomorrow_at, hm(l.start))
            else -> "${dayShort(l.start)} ${hm(l.start)}"
        }
    }
}

object Widgets {
    @Suppress("DEPRECATION")
    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c) ?: return
        val nextIds = m.getAppWidgetIds(ComponentName(c, NextClassWidget::class.java))
        for (id in nextIds) m.updateAppWidget(id, renderNext(c))
        val listIds = m.getAppWidgetIds(ComponentName(c, ScheduleWidget::class.java))
        for (id in listIds) m.updateAppWidget(id, renderSchedule(c, id))
        if (listIds.isNotEmpty()) m.notifyAppWidgetViewDataChanged(listIds, R.id.list)
        if (nextIds.isNotEmpty() || listIds.isNotEmpty()) scheduleNextRedraw(c)
    }

    fun openApp(c: Context, mutable: Boolean = false): PendingIntent {
        // The list template has to be mutable so each row can fill it in.
        val mutability = if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutability
        return PendingIntent.getActivity(c, if (mutable) 11 else 10, Intent(c, MainActivity::class.java), flags)
    }

    /** A short red line shown on the widgets when the timetable can't be loaded. */
    private fun warning(c: Context): String? =
        if (Store.lessons(c).isEmpty() && Store.getString(c, Store.SCHEDULE_ERR).isNotEmpty()) {
            c.getString(R.string.no_connection)
        } else {
            null
        }

    fun renderNext(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_next)
        v.setOnClickPendingIntent(R.id.root, openApp(c))
        val now = System.currentTimeMillis()
        val lesson = Store.lessons(c).firstOrNull { it.end > now }
        when {
            Store.icsUrl(c).isBlank() -> {
                v.setTextViewText(R.id.title, c.getString(R.string.no_link_title))
                v.setTextViewText(R.id.detail, c.getString(R.string.no_link_detail))
                v.setTextViewText(R.id.time, "")
            }
            lesson == null && Store.getLong(c, Store.SCHEDULE_AT) == 0L -> {
                v.setTextViewText(R.id.title, c.getString(R.string.loading))
                v.setTextViewText(R.id.detail, "")
                v.setTextViewText(R.id.time, "")
            }
            lesson == null -> {
                v.setTextViewText(R.id.title, c.getString(R.string.no_classes))
                v.setTextViewText(R.id.detail, c.getString(R.string.no_classes_detail))
                v.setTextViewText(R.id.time, "")
            }
            else -> {
                v.setTextViewText(R.id.title, lesson.title)
                v.setTextViewText(R.id.detail, lesson.detail)
                v.setTextViewText(R.id.time, Fmt.whenText(c, lesson, now))
            }
        }
        val warn = warning(c)
        v.setViewVisibility(R.id.status, if (warn != null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.status, warn ?: "")
        return v
    }

    @Suppress("DEPRECATION")
    fun renderSchedule(c: Context, widgetId: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_schedule)
        val service = Intent(c, ScheduleWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        v.setRemoteAdapter(R.id.list, service)
        v.setEmptyView(R.id.list, R.id.empty)
        v.setPendingIntentTemplate(R.id.list, openApp(c, mutable = true))
        v.setOnClickPendingIntent(R.id.header, openApp(c))
        v.setOnClickPendingIntent(R.id.empty, openApp(c))

        // Texts are set here (not in the layout) so they always follow the app language.
        v.setTextViewText(R.id.header_title, c.getString(R.string.widget_schedule))
        val updated = Store.getLong(c, Store.SCHEDULE_AT)
        v.setTextViewText(R.id.updated, if (updated > 0) c.getString(R.string.updated_at, Fmt.hm(updated)) else "")
        v.setTextViewText(
            R.id.empty,
            c.getString(
                when {
                    Store.icsUrl(c).isBlank() -> R.string.empty_no_link
                    updated == 0L -> R.string.loading
                    else -> R.string.no_classes
                }
            ),
        )
        val warn = warning(c)
        v.setViewVisibility(R.id.status, if (warn != null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.status, warn ?: "")
        return v
    }

    /** Redraw right when the current class ends or the next one starts (inexact, no extra permission). */
    private fun scheduleNextRedraw(c: Context) {
        val now = System.currentTimeMillis()
        val next = Store.lessons(c).asSequence()
            .flatMap { sequenceOf(it.start, it.end) }
            .filter { it > now }
            .minOrNull() ?: return
        val pi = PendingIntent.getBroadcast(
            c, 20, Intent(c, TickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        c.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, next + 1_000, pi)
    }
}

/** Fires at the start / end of a class: redraws the widgets from the saved timetable. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = Widgets.updateAll(c)
}

/** Shared behaviour of both widgets. */
abstract class BaseWidget : AppWidgetProvider() {
    /** Called by Android when a widget is added and then every 30 minutes (updatePeriodMillis). */
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Jobs.ensurePeriodic(c)
        Widgets.updateAll(c)
        if (Refresh.isStale(c)) Jobs.refreshSoon(c, force = false)
    }

    override fun onEnabled(c: Context) {
        Jobs.ensurePeriodic(c)
        Jobs.refreshSoon(c, force = true)
    }
}

/** Widget mode 1: only the current / next class. */
class NextClassWidget : BaseWidget()

/** Widget mode 2: full list of upcoming classes. */
class ScheduleWidget : BaseWidget()

class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = ScheduleFactory(applicationContext)
}

private class ScheduleFactory(private val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<Lesson> = emptyList()
    private var now = 0L

    override fun onCreate() {}
    override fun onDestroy() {}

    override fun onDataSetChanged() {
        now = System.currentTimeMillis()
        items = Store.lessons(c).filter { it.end > now }.take(80)
    }

    override fun getCount() = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_schedule_item)
        val l = items.getOrNull(position) ?: return v
        val prev = items.getOrNull(position - 1)
        val newDay = prev == null || !Fmt.sameDay(prev.start, l.start)
        v.setViewVisibility(R.id.day, if (newDay) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.day, Fmt.dayHeader(c, l.start, now))
        val running = l.start <= now
        v.setTextViewText(R.id.time, if (running) c.getString(R.string.now) else Fmt.hm(l.start))
        v.setTextColor(R.id.time, if (running) 0xFFC2410C.toInt() else 0xFF1B1A3A.toInt())
        v.setTextViewText(R.id.title, l.title)
        val until = c.getString(R.string.until, Fmt.hm(l.end))
        v.setTextViewText(R.id.detail, if (l.detail.isEmpty()) until else "${l.detail} · $until")
        v.setOnClickFillInIntent(R.id.item, Intent())
        return v
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
}
