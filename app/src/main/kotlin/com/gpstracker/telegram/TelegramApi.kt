package com.gpstracker.telegram

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object TelegramApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Sends a text message. Runs synchronously — call from background thread. */
    fun sendMessage(botToken: String, chatId: String, text: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "false")
            .build()

        val request = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    Log.e("TelegramApi", "Error ${response.code}: ${response.body?.string()}")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e("TelegramApi", "Network error: ${e.message}")
            false
        }
    }

    // ─── Message builders ─────────────────────────────────────────────────────

    fun buildBootMessage(): String {
        val time = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "📱 <b>Phone switched ON</b>\n\n🕐 Time: $time\n⏳ Acquiring GPS fix — location will follow shortly…"
    }

    fun buildOnlineWithLocationMessage(lat: Double, lng: Double, accuracy: Float): String {
        val mapsUrl = "https://www.google.com/maps?q=$lat,$lng"
        val time = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return """
📍 <b>Phone is online — current location</b>

🕐 Time: $time
🗺 <a href="$mapsUrl">Open in Google Maps</a>
Coordinates: <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>
Accuracy: ±${"%.0f".format(accuracy)} m

Tracking is active. You will be notified every 5 min if the phone moves more than 50 m.
        """.trimIndent()
    }

    fun buildLocationMessage(lat: Double, lng: Double, distanceMeters: Float, accuracy: Float): String {
        val mapsUrl = "https://www.google.com/maps?q=$lat,$lng"
        val distanceText = if (distanceMeters >= 1000) {
            "${"%.2f".format(distanceMeters / 1000f)} km"
        } else {
            "${"%.0f".format(distanceMeters)} m"
        }
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return """
📍 <b>Location Update — $time</b>

Phone is <b>$distanceText</b> from starting point.

🗺 <a href="$mapsUrl">Open in Google Maps</a>
Coordinates: <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>
Accuracy: ±${"%.0f".format(accuracy)} m
        """.trimIndent()
    }

    fun buildTestMessage(lat: Double?, lng: Double?): String {
        return if (lat != null && lng != null) {
            val mapsUrl = "https://www.google.com/maps?q=$lat,$lng"
            "✅ <b>GPS Tracker connected!</b>\n\n📍 Current location:\n<a href=\"$mapsUrl\">Open in Google Maps</a>\nCoordinates: <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>"
        } else {
            "✅ <b>GPS Tracker connected!</b>\n\n(Waiting for GPS fix — start tracking to get a location.)"
        }
    }
}
