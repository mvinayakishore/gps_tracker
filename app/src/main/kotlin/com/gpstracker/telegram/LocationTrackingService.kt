package com.gpstracker.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val CHANNEL_ID = "gps_tracker_channel"
        private const val NOTIFICATION_ID = 1001

        /** Minimum distance from start (meters) before Telegram alerts fire. */
        private const val ALERT_DISTANCE_METERS = 50f

        /** Minimum interval between Telegram messages (milliseconds). */
        private const val TELEGRAM_INTERVAL_MS = 5 * 60 * 1000L   // 5 minutes

        /** How often the FusedLocationProvider delivers updates. */
        private const val LOCATION_UPDATE_INTERVAL_MS = 30_000L    // 30 seconds
        private const val LOCATION_FASTEST_INTERVAL_MS = 15_000L   // 15 seconds
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The location captured when tracking first started (or after reboot). */
    private var startLocation: Location? = null

    /** Most recent GPS fix. */
    private var currentLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")

        startForeground(NOTIFICATION_ID, buildNotification("Acquiring GPS fix…"))

        // Mark tracking as active so BootReceiver can restart us after reboot
        getPrefs().edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, true).apply()

        // Restore start location if we were restarted after a reboot
        val prefs = getPrefs()
        val savedLat = prefs.getFloat(Prefs.KEY_START_LAT, Float.MIN_VALUE)
        val savedLng = prefs.getFloat(Prefs.KEY_START_LNG, Float.MIN_VALUE)
        if (savedLat != Float.MIN_VALUE && savedLng != Float.MIN_VALUE) {
            startLocation = Location("saved").apply {
                latitude  = savedLat.toDouble()
                longitude = savedLng.toDouble()
            }
            Log.d(TAG, "Restored start location from prefs")
        }

        startLocationUpdates()

        return START_STICKY   // Restart automatically if killed by the system
    }

    override fun onDestroy() {
        Log.d(TAG, "Service stopping")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        getPrefs().edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Location logic ──────────────────────────────────────────────────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onNewLocation(location)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun onNewLocation(location: Location) {
        currentLocation = location
        Log.d(TAG, "New location: ${location.latitude}, ${location.longitude}")

        // Persist latest fix so the Activity can read it
        getPrefs().edit()
            .putFloat(Prefs.KEY_CURRENT_LAT, location.latitude.toFloat())
            .putFloat(Prefs.KEY_CURRENT_LNG, location.longitude.toFloat())
            .apply()

        // Capture starting location on the very first fix
        if (startLocation == null) {
            startLocation = location
            getPrefs().edit()
                .putFloat(Prefs.KEY_START_LAT, location.latitude.toFloat())
                .putFloat(Prefs.KEY_START_LNG, location.longitude.toFloat())
                .apply()
            Log.d(TAG, "Starting location locked: ${location.latitude}, ${location.longitude}")
            updateNotification("Tracking active — waiting for movement…")
            return
        }

        val distanceMeters = startLocation!!.distanceTo(location)

        // Persist latest distance
        getPrefs().edit()
            .putFloat(Prefs.KEY_CURRENT_DIST, distanceMeters)
            .apply()

        val distanceText = if (distanceMeters >= 1000) {
            "${"%.2f".format(distanceMeters / 1000f)} km from start"
        } else {
            "${"%.0f".format(distanceMeters)} m from start"
        }
        updateNotification("Tracking — $distanceText")

        // Send Telegram alert when beyond threshold and interval has elapsed
        if (distanceMeters >= ALERT_DISTANCE_METERS) {
            val prefs = getPrefs()
            val lastSent = prefs.getLong(Prefs.KEY_LAST_SENT_TIME, 0L)
            val now = System.currentTimeMillis()

            if (now - lastSent >= TELEGRAM_INTERVAL_MS) {
                val botToken = prefs.getString(Prefs.KEY_BOT_TOKEN, "") ?: ""
                val chatId   = prefs.getString(Prefs.KEY_CHAT_ID, "")  ?: ""

                if (botToken.isNotBlank() && chatId.isNotBlank()) {
                    serviceScope.launch {
                        val message = TelegramApi.buildLocationMessage(
                            lat = location.latitude,
                            lng = location.longitude,
                            distanceMeters = distanceMeters,
                            accuracy = location.accuracy
                        )
                        val sent = TelegramApi.sendMessage(botToken, chatId, message)
                        if (sent) {
                            getPrefs().edit()
                                .putLong(Prefs.KEY_LAST_SENT_TIME, now)
                                .apply()
                            Log.d(TAG, "Telegram message sent at $now")
                        }
                    }
                }
            }
        }
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun getPrefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
}
