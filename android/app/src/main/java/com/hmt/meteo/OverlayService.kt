package com.hmt.meteo

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Floating weather widget, shown only on the home screen (UsageStatsManager).
 * Gestures: tap = open app, double tap = collapse to small icon (tap icon to restore), long press = drag.
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var tCity: TextView
    private lateinit var tDate: TextView
    private lateinit var tIcon: TextView
    private lateinit var tTemp: TextView
    private lateinit var tDesc: TextView
    private lateinit var tSub: TextView
    private lateinit var tBrand: TextView
    private lateinit var hourlyRow: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private var attached = false
    private var collapsed = false
    private lateinit var bubble: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var dragMode = false
    private var homePkgs = setOf<String>()
    private var foreground: String? = null
    private var lastPoll = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        homePkgs = launcherPackages(this)
        collapsed = Weather.prefs(this).getBoolean("collapsed", false)
        buildView()
        buildBubble()
        render()
        h.post(poll)
        h.postDelayed(autoRefresh, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE) render()
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (attached) runCatching { wm.removeView(current()) }
        attached = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "HMT METEO widget", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID).setContentTitle("HMT METEO")
            .setContentText("Weather widget active").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun bg(drag: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        colors = intArrayOf(Color.parseColor("#F0141418"), Color.parseColor("#F02A0A0A"))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(dp(if (drag) 3 else 1), if (drag) Color.parseColor("#E10600") else Color.parseColor("#55E10600"))
    }

    private fun buildView() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = bg(false)
            elevation = dp(8).toFloat()
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tCity = tv(17f, Color.WHITE, true)
        tDate = tv(13f, Color.parseColor("#AAAAAA"))
        header.addView(tCity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(tDate)
        root.addView(header)

        val main = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        tIcon = tv(56f, Color.WHITE)
        tTemp = tv(56f, Color.WHITE, true).apply { setPadding(dp(8), 0, dp(18), 0) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tDesc = tv(17f, Color.WHITE, true)
        tSub = tv(13f, Color.parseColor("#CCCCCC"))
        col.addView(tDesc); col.addView(tSub)
        main.addView(tIcon); main.addView(tTemp); main.addView(col)
        root.addView(main)

        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#33E10600"))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(2); bottomMargin = dp(8) })

        hourlyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(hourlyRow)

        tBrand = tv(9f, Color.parseColor("#E10600"), true).apply {
            text = "HMT METEO"; setPadding(0, dp(6), 0, 0)
        }
        root.addView(tBrand)

        val p = Weather.prefs(this)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("wx", dp(24)); y = p.getInt("wy", dp(24))
        }

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (dragMode) return true
                startActivity(Intent(this@OverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { setCollapsed(true); return true }
            override fun onLongPress(e: MotionEvent) { setDrag(true) }
        })

        var sx = 0; var sy = 0; var rx = 0f; var ry = 0f
        root.setOnTouchListener { _, e ->
            if (dragMode) {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; rx = e.rawX; ry = e.rawY; h.removeCallbacks(dragTimeout) }
                    MotionEvent.ACTION_MOVE -> {
                        if (rx == 0f && ry == 0f) { sx = params.x; sy = params.y; rx = e.rawX; ry = e.rawY }
                        params.x = sx + (e.rawX - rx).toInt(); params.y = sy + (e.rawY - ry).toInt()
                        if (attached) wm.updateViewLayout(root, params)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        Weather.prefs(this).edit().putInt("wx", params.x).putInt("wy", params.y).apply()
                        bubbleParams.x = params.x; bubbleParams.y = params.y
                        rx = 0f; ry = 0f
                        h.postDelayed(dragTimeout, 1500)
                    }
                }
                true
            } else gd.onTouchEvent(e)
        }
    }

    private val dragTimeout = Runnable { setDrag(false) }

    private fun setDrag(on: Boolean) {
        dragMode = on
        root.background = bg(on)
        root.alpha = if (on) 0.85f else 1f
        h.removeCallbacks(dragTimeout)
        if (on) h.postDelayed(dragTimeout, 8000)
    }

    private fun render() {
        val json = Weather.cached(this)
        val cur = json?.optJSONObject("current")
        if (cur == null) {
            tCity.text = "HMT METEO"; tDate.text = ""
            tIcon.text = "☁"; tTemp.text = "--°"
            tDesc.text = "No data yet"; tSub.text = "Open HMT METEO"
            hourlyRow.removeAllViews()
            bubble.text = "☁"
            return
        }
        bubble.text = Weather.icon(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1) +
            " ${cur.optDouble("temperature_2m").roundToInt()}°"
        val p = Weather.prefs(this)
        val code = cur.optInt("weather_code")
        val day = cur.optInt("is_day", 1) == 1
        tCity.text = p.getString("name", "") ?: ""
        tDate.text = SimpleDateFormat("EEE dd MMM  HH:mm", Locale.getDefault()).format(Date())
        tIcon.text = Weather.icon(code, day)
        tTemp.text = "${cur.optDouble("temperature_2m").roundToInt()}°"
        val stale = System.currentTimeMillis() - p.getLong("updated", 0) > Weather.STALE_MS
        tDesc.text = Weather.describe(code) + if (stale) "  • offline" else ""
        val daily = json.optJSONObject("daily")
        val feels = cur.optDouble("apparent_temperature").roundToInt()
        val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.roundToInt()
        val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.roundToInt()
        tSub.text = "Feels $feels°" + (if (min != null && max != null) "   ↓$min°  ↑$max°" else "")

        hourlyRow.removeAllViews()
        val hr = json.optJSONObject("hourly")
        if (hr != null) {
            val times = hr.getJSONArray("time")
            val now = cur.optString("time").take(13)
            val start = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= now } ?: 0
            for (i in start until minOf(start + 6, times.length())) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), 0, dp(4), 0)
                }
                item.addView(tv(11f, Color.parseColor("#AAAAAA")).apply { text = times.getString(i).substringAfter("T") })
                item.addView(tv(20f, Color.WHITE).apply { text = Weather.icon(hr.getJSONArray("weather_code").optInt(i), day) })
                item.addView(tv(14f, Color.WHITE, true).apply { text = "${hr.getJSONArray("temperature_2m").optDouble(i).roundToInt()}°" })
                val pp = hr.optJSONArray("precipitation_probability")
                if (pp != null && !pp.isNull(i) && pp.optInt(i) > 0)
                    item.addView(tv(10f, Color.parseColor("#7AB8FF")).apply { text = "${pp.optInt(i)}%" })
                hourlyRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }

    private val autoRefresh = object : Runnable {
        override fun run() {
            val p = Weather.prefs(this@OverlayService)
            if (System.currentTimeMillis() - p.getLong("updated", 0) > Weather.AUTO_REFRESH_MS && p.contains("lat")) {
                Thread {
                    runCatching {
                        Weather.fetch(this@OverlayService, p.getFloat("lat", 0f).toDouble(),
                            p.getFloat("lon", 0f).toDouble(), p.getString("name", "") ?: "")
                    }
                    h.post { render() }
                }.start()
            }
            h.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val show = if (hasUsageAccess(this@OverlayService)) {
                updateForeground()
                val fg = foreground
                fg == null || fg in homePkgs
            } else true
            setVisible(show)
            h.postDelayed(this, 1000)
        }
    }

    private fun updateForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastPoll == 0L) now - 60 * 60 * 1000L else lastPoll - 2000
        lastPoll = now
        val events = usm.queryEvents(from, now)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foreground = ev.packageName
        }
    }

    private fun current(): View = if (collapsed) bubble else root

    private fun setVisible(show: Boolean) {
        if (show && !attached) { runCatching { wm.addView(current(), if (collapsed) bubbleParams else params); attached = true; render() } }
        else if (!show && attached) { runCatching { wm.removeView(current()) }; attached = false }
    }

    private fun setCollapsed(c: Boolean) {
        if (c == collapsed) return
        val was = attached
        if (attached) { runCatching { wm.removeView(current()) }; attached = false }
        collapsed = c
        Weather.prefs(this).edit().putBoolean("collapsed", c).apply()
        if (was) setVisible(true)
    }

    private fun buildBubble() {
        bubble = tv(18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#F0141418"))
                setStroke(dp(2), Color.parseColor("#E10600"))
            }
            elevation = dp(6).toFloat()
            setOnClickListener { setCollapsed(false) }
        }
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = params.x; y = params.y }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "w"
        const val ACTION_UPDATE = "com.hmt.meteo.UPDATE"

        fun launcherPackages(ctx: Context): Set<String> {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
        }

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
