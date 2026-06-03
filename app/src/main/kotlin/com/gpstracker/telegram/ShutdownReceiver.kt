package com.gpstracker.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Catches ACTION_SHUTDOWN (device power-off / restart) and fires a
 * synchronous Telegram message before the OS kills all processes.
 *
 * Notes:
 *  - Must be synchronous (no coroutines) — the broadcast window is tiny.
 *  - goAsync() is intentionally NOT used because the OS won't wait for it
 *    during a real power-off; a blocking Thread with a tight timeout is
 *    the most reliable approach.
 *  - Not guaranteed on every ROM (some Samsung/Xiaomi ROMs give <200 ms),
 *    but works correctly on normal shutdowns and restarts.
 */
class ShutdownReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SHUTDOWN &&
            action != "android.intent.action.QUICKBOOT_POWEROFF" &&
            action != "com.htc.intent.action.QUICKBOOT_POWEROFF"
        ) return

        Log.d("ShutdownReceiver", "Shutdown event: $action")

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val botToken = prefs.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN)
            ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId = prefs.getString(Prefs.KEY_CHAT_ID, Prefs.DEFAULT_CHAT_ID)
            ?: Prefs.DEFAULT_CHAT_ID

        if (botToken.isBlank() || chatId.isBlank()) return

        // Block for up to 4 s — the OS typically allows 5 s before force-kill
        val battery = BatteryUtils.getInfo(context)
        val thread = Thread {
            try {
                TelegramApi.sendMessage(botToken, chatId, TelegramApi.buildShutdownMessage(battery))
            } catch (e: Exception) {
                Log.e("ShutdownReceiver", "Failed to send shutdown message: ${e.message}")
            }
        }
        thread.isDaemon = false
        thread.start()
        thread.join(4_000)
    }
}
