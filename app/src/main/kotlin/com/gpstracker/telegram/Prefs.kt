package com.gpstracker.telegram

/**
 * Shared SharedPreferences keys and default credential values.
 */
object Prefs {
    const val NAME = "gps_tracker_prefs"

    const val KEY_BOT_TOKEN       = "bot_token"
    const val KEY_CHAT_ID         = "chat_id"
    const val KEY_TRACKING_ACTIVE = "tracking_active"

    const val KEY_START_LAT       = "start_lat"
    const val KEY_START_LNG       = "start_lng"
    const val KEY_LAST_SENT_TIME  = "last_sent_time"
    const val KEY_CURRENT_LAT     = "current_lat"
    const val KEY_CURRENT_LNG     = "current_lng"
    const val KEY_CURRENT_DIST    = "current_distance"

    // Pre-configured Telegram credentials
    const val DEFAULT_BOT_TOKEN = "8846592056:AAEEdBt635UUnb9ySGeiQXYPoWSc7Fu_7G8"
    const val DEFAULT_CHAT_ID   = "1844502473"
}
