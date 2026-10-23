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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Floating weather widget. Shown only while the launcher (home screen) is in the
 * foreground, detected with UsageStatsManager (requires "Usage access").
 * Without usage access it stays visible everywhere (fallback).
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var tIcon: TextView
    private lateinit var tTemp: TextView
    private lateinit var tDesc: TextView
    private lateinit var tCity: TextView
    private val h = Handler(Looper.getMainLooper())
    private var attached = false
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
        buildView()
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
        if (attached) runCatching { wm.removeView(root) }
        attached = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("w", "HMT METEO widget", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "w").setContentTitle("HMT METEO")
            .setContentText("Weather widget active").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun bg(drag: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        colors = intArrayOf(Color.parseColor("#E6141418"), Color.parseColor("#E62A0A0A"))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(dp(if (drag) 3 else 1), if (drag) Color.parseColor("#E10600") else Color.parseColor("#55E10600"))
    }

    private fun buildView() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(20), dp(10))
            background = bg(false)
            elevation = dp(8).toFloat()
        }
        tIcon = tv(34f, Color.WHITE)
        tTemp = tv(34f, Color.WHITE, true).apply { setPadding(dp(10), 0, dp(14), 0) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tDesc = tv(14f, Color.parseColor("#DDDDDD"))
        tCity = tv(13f, Color.parseColor("#999999"))
        val brand = tv(9f, Color.parseColor("#E10600"), true).apply { text = "HMT METEO" }
        col.addView(tDesc); col.addView(tCity); col.addView(brand)
        root.addView(tIcon); root.addView(tTemp); root.addView(col)

        val p = Weather.prefs(this)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("wx", dp(40)); y = p.getInt("wy", dp(40))
        }

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (dragMode) return true
                startActivity(Intent(this@OverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                Weather.prefs(this@OverlayService).edit().putBoolean("widget_on", false).apply()
                stopSelf(); return true
            }
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
            tIcon.text = "☁"; tTemp.text = "--°"; tDesc.text = "No data yet"; tCity.text = "Open HMT METEO"
            return
        }
        val code = cur.optInt("weather_code")
        tIcon.text = Weather.icon(code, cur.optInt("is_day", 1) == 1)
        tTemp.text = "${cur.optDouble("temperature_2m").roundToInt()}°"
        val stale = System.currentTimeMillis() - Weather.prefs(this).getLong("updated", 0) > Weather.STALE_MS
        tDesc.text = Weather.describe(code) + if (stale) "  • offline" else ""
        tCity.text = Weather.prefs(this).getString("name", "") ?: ""
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

    private fun setVisible(show: Boolean) {
        if (show && !attached) { runCatching { wm.addView(root, params); attached = true; render() } }
        else if (!show && attached) { runCatching { wm.removeView(root) }; attached = false }
    }

    companion object {
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
