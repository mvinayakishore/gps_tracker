package com.gpstracker.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Catches charger connect/disconnect and low-battery events and sends
 * Telegram alerts immediately.
 *
 * ACTION_POWER_CONNECTED, ACTION_POWER_DISCONNECTED, ACTION_BATTERY_LOW,
 * and ACTION_BATTERY_OKAY are all in Android's implicit-broadcast exemption
 * list, so they can be declared in the manifest and wake the app even when
 * it isn't running.
 */
class BatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_POWER_CONNECTED &&
            action != Intent.ACTION_POWER_DISCONNECTED &&
            action != Intent.ACTION_BATTERY_LOW &&
            action != Intent.ACTION_BATTERY_OKAY
        ) return

        val prefs    = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val botToken = prefs.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN)
            ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId   = prefs.getString(Prefs.KEY_CHAT_ID, Prefs.DEFAULT_CHAT_ID)
            ?: Prefs.DEFAULT_CHAT_ID

        if (botToken.isBlank() || chatId.isBlank()) return

        val battery = BatteryUtils.getInfo(context)
        val message = when (action) {
            Intent.ACTION_POWER_CONNECTED    -> TelegramApi.buildChargerConnectedMessage(battery)
            Intent.ACTION_POWER_DISCONNECTED -> TelegramApi.buildChargerDisconnectedMessage(battery)
            Intent.ACTION_BATTERY_LOW        -> TelegramApi.buildBatteryLowMessage(battery)
            Intent.ACTION_BATTERY_OKAY       -> TelegramApi.buildBatteryOkayMessage(battery)
            else -> return
        }

        val pending = goAsync()
        Thread {
            try {
                TelegramApi.sendMessage(botToken, chatId, message)
            } catch (e: Exception) {
                Log.e("BatteryReceiver", "send failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.also { it.isDaemon = false; it.start() }
    }
}
