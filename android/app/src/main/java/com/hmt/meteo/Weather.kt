package com.hmt.meteo

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Weather {
    private const val PREFS = "hmt_meteo"
    const val MIN_REFRESH_MS = 5 * 60 * 1000L
    const val AUTO_REFRESH_MS = 30 * 60 * 1000L
    const val STALE_MS = 90 * 60 * 1000L

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "HMT-METEO/1.0")
        try {
            if (c.responseCode !in 200..299) throw Exception("Weather service error ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun fetch(ctx: Context, lat: Double, lon: Double, name: String): JSONObject {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
            "&timezone=auto&forecast_days=6"
        val json = JSONObject(http(url))
        prefs(ctx).edit()
            .putString("data", json.toString())
            .putString("name", name)
            .putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat())
            .putLong("updated", System.currentTimeMillis())
            .apply()
        return json
    }

    fun cached(ctx: Context): JSONObject? =
        prefs(ctx).getString("data", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    data class City(val name: String, val lat: Double, val lon: Double)

    fun searchCity(q: String): List<City> {
        val json = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?count=8&language=fr&name=" +
            URLEncoder.encode(q, "UTF-8")))
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            City("${o.getString("name")}, ${o.optString("country")}", o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"; 1 -> "Mainly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"; 66, 67 -> "Freezing rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"; 95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"; else -> "Unknown"
    }

    fun icon(code: Int, day: Boolean = true): String = when (code) {
        0, 1 -> if (day) "☀" else "☾"
        2 -> "⛅"; 3 -> "☁"; 45, 48 -> "≋"
        in 51..67, in 80..82 -> "☂"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⚡"; else -> "☁"
    }

    fun compass(deg: Double): String {
        val d = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return d[(((deg % 360) + 22.5) / 45).toInt() % 8]
    }
}
