package com.hmt.meteo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val RED = Color.parseColor("#E10600")
    private val CARD = Color.parseColor("#17171B")
    private val GREY = Color.parseColor("#9A9AA2")
    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0B0D")) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        scroll.addView(page)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("HMT", 26f, RED, true))
        header.addView(text(" METEO", 26f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        header.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(button("REFRESH", true) { refresh(true) })
        page.addView(header)
        status = text("", 13f, GREY).apply { setPadding(0, dp(4), 0, dp(12)) }
        page.addView(status)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)

        val actions = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        actions.addView(button("WIDGET ON / OFF", false) { toggleWidget() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(space())
        actions.addView(button("SEARCH CITY", false) { searchCity() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(space())
        actions.addView(button("USE GPS", false) {
            Weather.prefs(this).edit().putBoolean("manual", false).apply(); refresh(true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(actions)

        val about = card().apply {
            addView(text("About", 16f, Color.WHITE, true))
            addView(text("Widget gestures: tap = open app • double tap = hide widget • long press = move widget.\n" +
                "To show the widget only on the home screen, allow \"Usage access\" for HMT METEO. " +
                "Without it the widget stays visible over all apps.", 13f, GREY))
            addView(text("Weather data by Open-Meteo.com (CC BY 4.0)", 12f, GREY))
            addView(text("HMT METEO — © 2026 HMT Auto Solutions. All rights reserved.", 12f, RED, true))
        }
        page.addView(about, lp(top = 16))

        setContentView(scroll)
        render()
        refresh(false)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun round(color: Int, r: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(CARD); setPadding(dp(18), dp(16), dp(18), dp(16)); elevation = dp(4).toFloat()
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) }
    private fun button(label: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = round(if (primary) RED else Color.parseColor("#26262C"), 14)
        setPadding(dp(22), dp(12), dp(22), dp(12)); setOnClickListener { on() }
    }

    private fun setBusy(b: Boolean) { busy = b; progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun refresh(user: Boolean) {
        if (busy) return
        val p = Weather.prefs(this)
        if (!user && System.currentTimeMillis() - p.getLong("updated", 0) < Weather.MIN_REFRESH_MS) return
        if (p.getBoolean("manual", false) && p.contains("lat")) {
            fetch(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), p.getString("name", "") ?: ""); return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        setBusy(true); status.text = "Locating…"
        Loc.get(this) { l ->
            if (l == null) {
                setBusy(false)
                if (p.contains("lat")) fetch(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), p.getString("name", "") ?: "")
                else { status.text = "GPS unavailable — use SEARCH CITY"; searchCity() }
                return@get
            }
            Thread {
                val name = Loc.name(this, l)
                h.post { setBusy(false); fetch(l.latitude, l.longitude, name) }
            }.start()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        if (res.any { it == PackageManager.PERMISSION_GRANTED }) refresh(true)
        else { status.text = "Location denied — use SEARCH CITY"; render() }
    }

    private fun fetch(lat: Double, lon: Double, name: String) {
        setBusy(true); status.text = "Updating weather…"
        Thread {
            val err = try { Weather.fetch(this, lat, lon, name); null } catch (e: Exception) { e }
            h.post {
                setBusy(false); render()
                if (Weather.prefs(this).getBoolean("widget_on", false))
                    startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_UPDATE))
                if (err != null) {
                    status.text = "Offline — weather service unreachable. Showing last saved data."
                    Toast.makeText(this, "No connection: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun searchCity() {
        val input = EditText(this).apply { hint = "City name" }
        AlertDialog.Builder(this).setTitle("Search city").setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim(); if (q.isEmpty()) return@setPositiveButton
                Thread {
                    val r = runCatching { Weather.searchCity(q) }
                    h.post {
                        val list = r.getOrNull()
                        if (list.isNullOrEmpty()) { Toast.makeText(this, "No city found / offline", Toast.LENGTH_LONG).show(); return@post }
                        AlertDialog.Builder(this).setTitle("Select city")
                            .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                                Weather.prefs(this).edit().putBoolean("manual", true).apply()
                                fetch(list[i].lat, list[i].lon, list[i].name)
                            }.show()
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toggleWidget() {
        val p = Weather.prefs(this)
        if (p.getBoolean("widget_on", false)) {
            p.edit().putBoolean("widget_on", false).apply()
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Widget off", Toast.LENGTH_SHORT).show(); return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this).setTitle("Permission needed")
                .setMessage("Allow \"Display over other apps\" so HMT METEO can show its widget on the home screen.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.setNegativeButton("Cancel", null).show()
            return
        }
        if (!OverlayService.hasUsageAccess(this)) {
            AlertDialog.Builder(this).setTitle("Home screen only")
                .setMessage("To show the widget only on the home screen, allow \"Usage access\" for HMT METEO. Without it, the widget stays visible over all apps.")
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }.setNegativeButton("Skip") { _, _ -> startWidget() }.show()
            return
        }
        startWidget()
    }

    private fun startWidget() {
        Weather.prefs(this).edit().putBoolean("widget_on", true).apply()
        startForegroundService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Widget on — press Home to see it", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        content.removeAllViews()
        val p = Weather.prefs(this)
        val json = Weather.cached(this)
        if (json == null) { content.addView(card().apply { addView(text("No weather data yet. Press REFRESH.", 18f, Color.WHITE)) }); return }
        val upd = p.getLong("updated", 0)
        val stale = System.currentTimeMillis() - upd > Weather.STALE_MS
        if (!busy) status.text = "Updated " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(upd)) +
            (if (stale) "  •  STALE / OFFLINE DATA" else "")

        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val code = cur.optInt("weather_code"); val day = cur.optInt("is_day", 1) == 1

        val main = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val hero = card().apply {
            addView(text(p.getString("name", "") ?: "", 18f, GREY))
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(Weather.icon(code, day), 64f, Color.WHITE))
            row.addView(text(" ${cur.optDouble("temperature_2m").roundToInt()}°C", 64f, Color.WHITE, true))
            addView(row)
            addView(text(Weather.describe(code), 22f, Color.WHITE))
            addView(text("Feels like ${cur.optDouble("apparent_temperature").roundToInt()}°  •  " +
                "Min ${daily.getJSONArray("temperature_2m_min").optDouble(0).roundToInt()}°  " +
                "Max ${daily.getJSONArray("temperature_2m_max").optDouble(0).roundToInt()}°", 16f, GREY))
        }
        main.addView(hero, LinearLayout.LayoutParams(0, -1, 1.3f))
        main.addView(space())
        val det = card().apply {
            fun row(k: String, v: String) = addView(LinearLayout(this@MainActivity).apply {
                setPadding(0, dp(4), 0, dp(4))
                addView(text(k, 16f, GREY), LinearLayout.LayoutParams(0, -2, 1f)); addView(text(v, 16f, Color.WHITE, true))
            })
            row("Humidity", "${cur.optInt("relative_humidity_2m")}%")
            row("Wind", "${cur.optDouble("wind_speed_10m").roundToInt()} km/h ${Weather.compass(cur.optDouble("wind_direction_10m"))}")
            daily.optJSONArray("precipitation_probability_max")?.let { if (!it.isNull(0)) row("Precipitation", "${it.optInt(0)}%") }
            row("Sunrise", daily.getJSONArray("sunrise").optString(0).substringAfter("T"))
            row("Sunset", daily.getJSONArray("sunset").optString(0).substringAfter("T"))
        }
        main.addView(det, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(main)

        content.addView(text("HOURLY", 14f, RED, true).apply { setPadding(0, dp(18), 0, dp(8)) })
        content.addView(hourly(json))
        content.addView(text("5-DAY FORECAST", 14f, RED, true).apply { setPadding(0, dp(18), 0, dp(8)) })
        val days = LinearLayout(this)
        val fmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.US); val fmtOut = SimpleDateFormat("EEE dd", Locale.getDefault())
        val times = daily.getJSONArray("time")
        for (i in 1 until minOf(6, times.length())) {
            val c = daily.getJSONArray("weather_code").optInt(i)
            val label = runCatching { fmtOut.format(fmtIn.parse(times.getString(i))!!) }.getOrDefault(times.getString(i))
            days.addView(card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(label, 14f, GREY)); addView(text(Weather.icon(c), 30f, Color.WHITE))
                addView(text("${daily.getJSONArray("temperature_2m_max").optDouble(i).roundToInt()}° / " +
                    "${daily.getJSONArray("temperature_2m_min").optDouble(i).roundToInt()}°", 15f, Color.WHITE, true))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        }
        content.addView(days)
    }

    private fun hourly(json: JSONObject): View {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this)
        val hr = json.getJSONObject("hourly")
        val times = hr.getJSONArray("time")
        val now = json.getJSONObject("current").optString("time").take(13)
        val start = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= now } ?: 0
        val pp = hr.optJSONArray("precipitation_probability")
        for (i in start until minOf(start + 24, times.length())) {
            row.addView(card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(times.getString(i).substringAfter("T"), 13f, GREY))
                addView(text(Weather.icon(hr.getJSONArray("weather_code").optInt(i)), 24f, Color.WHITE))
                addView(text("${hr.getJSONArray("temperature_2m").optDouble(i).roundToInt()}°", 16f, Color.WHITE, true))
                if (pp != null && !pp.isNull(i)) addView(text("${pp.optInt(i)}%", 12f, GREY))
            }, LinearLayout.LayoutParams(dp(78), -2).apply { marginEnd = dp(8) })
        }
        hs.addView(row); return hs
    }

    override fun onResume() { super.onResume(); render() }
}
