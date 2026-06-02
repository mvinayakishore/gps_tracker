package com.gpstracker.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmManager-based watchdog. Receives a periodic alarm and
 * restarts LocationTrackingService if it should be running.
 * This is a secondary layer of resilience on top of WorkManager.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val shouldRun = prefs.getBoolean(Prefs.KEY_TRACKING_ACTIVE, false)

        if (shouldRun) {
            Log.d("WatchdogReceiver", "Alarm watchdog fired — restarting service if needed")
            val svc = Intent(context, LocationTrackingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (e: Exception) {
                Log.e("WatchdogReceiver", "Start failed: ${e.message}")
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.gpstracker.telegram.WATCHDOG"
    }
}
