package com.gpstracker.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d("BootReceiver", "Boot event received: $action")

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val botToken = prefs.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN) ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId   = prefs.getString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID)   ?: Prefs.DEFAULT_CHAT_ID

        // Mark that the location-on-boot has not yet been sent so the service
        // will send it once it gets a GPS fix
        prefs.edit()
            .putBoolean(Prefs.KEY_TRACKING_ACTIVE, true)
            .putBoolean(Prefs.KEY_BOOT_LOCATION_SENT, false)
            .remove(Prefs.KEY_START_LAT)
            .remove(Prefs.KEY_START_LNG)
            .remove(Prefs.KEY_CURRENT_DIST)
            .remove(Prefs.KEY_LAST_SENT_TIME)
            .apply()

        // Send "phone switched on" message immediately (no location yet)
        if (botToken.isNotBlank() && chatId.isNotBlank()) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    TelegramApi.sendMessage(botToken, chatId, TelegramApi.buildBootMessage())
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to send boot message: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
        }

        // Start the tracking service — it will send the location once GPS is acquired
        val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
            putExtra(LocationTrackingService.EXTRA_BOOT_START, true)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start service: ${e.message}")
        }
    }
}
