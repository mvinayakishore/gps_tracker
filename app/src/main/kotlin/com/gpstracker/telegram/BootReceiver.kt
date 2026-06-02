package com.gpstracker.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the LocationTrackingService after a device reboot,
 * but only if the user had tracking enabled before the reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d("BootReceiver", "Boot completed, checking if tracking was active")

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val wasTracking = prefs.getBoolean(Prefs.KEY_TRACKING_ACTIVE, false)

        if (wasTracking) {
            val botToken = prefs.getString(Prefs.KEY_BOT_TOKEN, "") ?: ""
            val chatId  = prefs.getString(Prefs.KEY_CHAT_ID, "")  ?: ""

            if (botToken.isNotBlank() && chatId.isNotBlank()) {
                Log.d("BootReceiver", "Restarting LocationTrackingService after boot")
                val serviceIntent = Intent(context, LocationTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d("BootReceiver", "Credentials missing — not restarting service")
                prefs.edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, false).apply()
            }
        }
    }
}
