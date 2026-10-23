package com.hmt.meteo

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Locale

object Loc {
    @SuppressLint("MissingPermission")
    fun get(ctx: Context, cb: (Location?) -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val provider = when {
            providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { cb(last); return }
        val h = Handler(Looper.getMainLooper())
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { if (!done) { done = true; lm.removeUpdates(this); cb(l) } }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: Exception) { cb(last); return }
        h.postDelayed({ if (!done) { done = true; lm.removeUpdates(listener); cb(last) } }, 15000)
    }

    fun name(ctx: Context, l: Location): String = try {
        @Suppress("DEPRECATION")
        val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
        a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: fmt(l)
    } catch (e: Exception) { fmt(l) }

    private fun fmt(l: Location) = String.format(Locale.US, "%.3f, %.3f", l.latitude, l.longitude)
}
