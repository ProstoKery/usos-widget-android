package pl.kera.usoswidget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Emulator-test screen: hosts both real widgets the same way a home screen does,
 * because the emulator's launcher can't pin widgets from adb.
 * Needs `adb shell appwidget grantbind --package pl.kera.usoswidget`.
 */
class WidgetPreviewActivity : Activity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 4711)
        host.deleteHost() // start from a clean slate on every launch
        val awm = AppWidgetManager.getInstance(this)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (40 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            setBackgroundColor(0xFF2B3550.toInt())
        }
        val widgets = listOf(
            Triple(NextClassWidget::class.java, R.string.widget_next, Pair(170, 170)),
            Triple(ScheduleWidget::class.java, R.string.widget_schedule, Pair(340, 300)),
        )
        for ((cls, label, size) in widgets) {
            col.addView(TextView(this).apply {
                text = getString(label)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
            })
            val id = host.allocateAppWidgetId()
            if (!awm.bindAppWidgetIdIfAllowed(id, ComponentName(this, cls))) {
                col.addView(TextView(this).apply { text = "bind not allowed"; setTextColor(Color.RED) })
                continue
            }
            val view = host.createView(applicationContext, id, awm.getAppWidgetInfo(id))
            col.addView(view, LinearLayout.LayoutParams((size.first * d).toInt(), (size.second * d).toInt()))
        }
        setContentView(col)
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
        Handler(Looper.getMainLooper()).postDelayed({ Thread { Widgets.updateAll(applicationContext) }.start() }, 500)
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }
}
