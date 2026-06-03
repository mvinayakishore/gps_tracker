package com.gpstracker.telegram

import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TelegramApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun now(): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())

    private fun time(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    // ─── Text message ─────────────────────────────────────────────────────────

    fun sendMessage(botToken: String, chatId: String, text: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "false")
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendMessage")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            Log.e("TelegramApi", "sendMessage error: ${e.message}")
            false
        }
    }

    // ─── Photo message ────────────────────────────────────────────────────────

    fun sendPhoto(
        botToken: String,
        chatId: String,
        photoBytes: ByteArray,
        caption: String
    ): Boolean {
        if (botToken.isBlank() || chatId.isBlank() || photoBytes.isEmpty()) return false
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart(
                "photo", "photo.jpg",
                photoBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()!!)
            )
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendPhoto")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            Log.e("TelegramApi", "sendPhoto error: ${e.message}")
            false
        }
    }

    // ─── Message builders ─────────────────────────────────────────────────────

    fun buildInstalledMessage(): String = """
🔒 <b>GPS Tracker — Active</b>

Device is now being monitored.
📅 Activated: ${now()}

You will receive:
• Location on every boot
• Location + front &amp; rear photos every 5 min when device moves 50 m+
• Location + front &amp; rear photos every 30 min when stationary
• Notification if tracker is stopped
    """.trimIndent()

    fun buildPhotoCaption(lat: Double, lng: Double, reason: String): String {
        val maps = "https://www.google.com/maps?q=$lat,$lng"
        return "📍 <a href=\"$maps\">${"%.5f".format(lat)}, ${"%.5f".format(lng)}</a>  •  $reason  •  ${time()}"
    }

    fun buildBootMessage(): String = """
📱 <b>Phone switched ON</b>

🕐 Time: ${now()}
⏳ Acquiring GPS fix — location will follow shortly…
    """.trimIndent()

    fun buildOnlineWithLocationMessage(lat: Double, lng: Double, accuracy: Float): String {
        val maps = "https://www.google.com/maps?q=$lat,$lng"
        return """
📍 <b>Phone is online — current location</b>

🕐 ${now()}
🗺 <a href="$maps">Open in Google Maps</a>
📌 <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>
🎯 Accuracy: ±${"%.0f".format(accuracy)} m

Tracking active. Alerts every 5 min if moved 50 m+.
        """.trimIndent()
    }

    fun buildLocationMessage(
        lat: Double, lng: Double,
        distanceMeters: Float, accuracy: Float
    ): String {
        val maps = "https://www.google.com/maps?q=$lat,$lng"
        val dist = if (distanceMeters >= 1000)
            "${"%.2f".format(distanceMeters / 1000f)} km"
        else
            "${"%.0f".format(distanceMeters)} m"
        return """
📍 <b>Location Update — ${time()}</b>

Device moved <b>$dist</b> from starting point.

🗺 <a href="$maps">Open in Google Maps</a>
📌 <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>
🎯 Accuracy: ±${"%.0f".format(accuracy)} m
        """.trimIndent()
    }

    fun buildStationaryMessage(lat: Double, lng: Double, accuracy: Float): String {
        val maps = "https://www.google.com/maps?q=$lat,$lng"
        return """
🟢 <b>Status: No movement detected</b>

Device is stationary. Current location:
🗺 <a href="$maps">Open in Google Maps</a>
📌 <code>${"%.6f".format(lat)}, ${"%.6f".format(lng)}</code>
🎯 Accuracy: ±${"%.0f".format(accuracy)} m
🕐 ${now()}
        """.trimIndent()
    }

    fun buildClosedMessage(): String = """
⚠️ <b>Tracker was stopped</b>

🕐 ${now()}
Attempting to restart automatically…
    """.trimIndent()
}
