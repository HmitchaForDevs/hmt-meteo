package com.hmt.meteo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Weather.prefs(ctx).getBoolean("widget_on", false) && Settings.canDrawOverlays(ctx)) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }
    }
}
