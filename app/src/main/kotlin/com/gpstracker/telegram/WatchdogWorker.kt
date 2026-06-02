package com.gpstracker.telegram

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that runs every 15 minutes to ensure
 * LocationTrackingService is alive. If the service was killed
 * by the OS, this restarts it silently.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean(Prefs.KEY_TRACKING_ACTIVE, false)

        if (shouldBeRunning) {
            Log.d("WatchdogWorker", "Watchdog ping — ensuring service is running")
            val intent = Intent(applicationContext, LocationTrackingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("WatchdogWorker", "Failed to start service: ${e.message}")
            }
        }
        return Result.success()
    }
}
