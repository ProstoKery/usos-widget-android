package pl.kera.usoswidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The only screen: paste the USOS link, add the widgets, see the current state. */
class MainActivity : Activity() {
    private lateinit var urlField: EditText
    private lateinit var linkState: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Jobs.ensurePeriodic(this)

        val pad = dp(18)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(header(getString(R.string.app_name), 24f, top = 0))

        // 1. Link
        col.addView(header(getString(R.string.step_link)))
        col.addView(body(getString(R.string.step_link_hint)))
        linkState = body("").apply { setTypeface(typeface, Typeface.BOLD) }
        col.addView(linkState)
        urlField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        col.addView(urlField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(button(getString(R.string.save)) {
            val link = Schedule.extractLink(urlField.text.toString())
            when {
                urlField.text.isBlank() -> toast(getString(R.string.paste_first))
                link == null -> toast(getString(R.string.bad_link))
                else -> saveLink(link)
            }
        })
        col.addView(small(getString(R.string.link_private)))

        // 2. Widgets
        col.addView(header(getString(R.string.step_widgets)))
        val awm = AppWidgetManager.getInstance(this)
        if (awm != null && awm.isRequestPinAppWidgetSupported) {
            col.addView(body(getString(R.string.step_widgets_pin)))
            col.addView(button(getString(R.string.add_widget, getString(R.string.widget_next))) {
                awm.requestPinAppWidget(ComponentName(this, NextClassWidget::class.java), null, null)
            })
            col.addView(button(getString(R.string.add_widget, getString(R.string.widget_schedule))) {
                awm.requestPinAppWidget(ComponentName(this, ScheduleWidget::class.java), null, null)
            })
        }
        col.addView(body(getString(R.string.step_widgets_hint, getString(R.string.widget_next), getString(R.string.widget_schedule))))

        // State
        col.addView(header(getString(R.string.status)))
        status = body("")
        status.setTextIsSelectable(true)
        col.addView(status)
        col.addView(button(getString(R.string.refresh)) {
            toast(getString(R.string.refreshing))
            refreshInBackground(force = true)
        })

        // Battery tip (some phones stop background work of rarely opened apps)
        col.addView(small(getString(R.string.battery_hint)).apply { setPadding(0, dp(20), 0, dp(4)) })
        col.addView(button(getString(R.string.battery_button)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        // Opening the app (e.g. by tapping a widget) redraws the widgets and refreshes old data.
        if (Refresh.isStale(this)) refreshInBackground(force = false) else Thread { Widgets.updateAll(applicationContext) }.start()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** A link shared into the app ("Share → USOS Widget") or a tapped webcal:// link. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val link = when (i.action) {
            Intent.ACTION_SEND -> Schedule.extractLink(i.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> i.dataString?.takeIf { it.startsWith("webcal", ignoreCase = true) }
            else -> null
        } ?: return
        i.action = null // handle once, not again after rotation
        saveLink(link)
    }

    private fun saveLink(link: String) {
        Store.setIcsUrl(this, link)
        urlField.setText("")
        toast(getString(R.string.saved))
        refreshInBackground(force = true)
        refreshStatus()
    }

    private fun refreshInBackground(force: Boolean) {
        val app = applicationContext
        Thread {
            Refresh.run(app, force)
            runOnUiThread { if (!isFinishing) refreshStatus() }
        }.start()
    }

    private fun refreshStatus() {
        val hasLink = Store.icsUrl(this).isNotBlank()
        // The saved link is never shown in full: it contains a private key and people share screenshots.
        linkState.text = if (hasLink) getString(R.string.link_saved) else ""
        linkState.visibility = if (hasLink) View.VISIBLE else View.GONE
        urlField.hint = getString(if (hasLink) R.string.link_replace_hint else R.string.link_field_hint)

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        val lessons = Store.lessons(this)
        val scheduleAt = Store.getLong(this, Store.SCHEDULE_AT)
        sb.append(getString(R.string.st_schedule)).append(' ')
        when {
            !hasLink -> sb.append(getString(R.string.st_no_link))
            scheduleAt == 0L && lessons.isEmpty() -> sb.append(getString(R.string.loading))
            else -> sb.append(
                getString(R.string.st_ok, lessons.count { it.end > now }, "${Fmt.dayShort(scheduleAt)} ${Fmt.hm(scheduleAt)}")
            )
        }
        Store.getString(this, Store.SCHEDULE_ERR).takeIf { it.isNotEmpty() }?.let { sb.append("\n⚠ ").append(it) }
        lessons.firstOrNull { it.end > now }?.let {
            sb.append('\n').append(getString(R.string.st_next, it.title, it.detail, Fmt.whenText(this, it, now)))
        }
        status.text = sb.toString()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun header(s: String, size: Float = 17f, top: Int = 22) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(4))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun small(s: String) = body(s).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = 0.7f
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
