package com.gpstracker.telegram

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object TelegramApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a text message to the given chat via the Bot API.
     * Runs synchronously — call from a background thread.
     * Returns true on success.
     */
    fun sendMessage(botToken: String, chatId: String, text: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "false")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

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

    /**
     * Builds the alert message with a Google Maps link.
     */
    fun buildLocationMessage(
        lat: Double,
        lng: Double,
        distanceMeters: Float,
        accuracy: Float
    ): String {
        val mapsUrl = "https://www.google.com/maps?q=$lat,$lng"
        val distanceText = if (distanceMeters >= 1000) {
            "%.2f km".format(distanceMeters / 1000f)
        } else {
            "%.0f m".format(distanceMeters)
        }

        return """
📍 <b>GPS Tracker Alert</b>

You are <b>$distanceText</b> from your starting point.

🗺 <a href="$mapsUrl">Open in Google Maps</a>
Coordinates: <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>
Accuracy: ±${"%.0f".format(accuracy)} m
        """.trimIndent()
    }

    /**
     * Builds a simple test message.
     */
    fun buildTestMessage(lat: Double?, lng: Double?): String {
        return if (lat != null && lng != null) {
            val mapsUrl = "https://www.google.com/maps?q=$lat,$lng"
            "✅ <b>GPS Tracker</b> is connected!\n\n📍 Current location:\n<a href=\"$mapsUrl\">Open in Google Maps</a>\nCoordinates: <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>"
        } else {
            "✅ <b>GPS Tracker</b> is connected!\n\n(No GPS fix yet — start tracking to get a location.)"
        }
    }
}
