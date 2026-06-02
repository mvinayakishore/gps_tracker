package com.gpstracker.telegram

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.gpstracker.telegram.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 5_000)   // refresh UI every 5 s
        }
    }

    // ─── Permission launchers ─────────────────────────────────────────────────

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocation()
            } else {
                startTrackingService()
            }
        } else {
            showPermissionDeniedDialog()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startTrackingService()
        } else {
            showBackgroundPermissionDialog()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notifications optional — continue regardless */ }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreCredentials()
        setupButtons()

        // Ask for notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        saveCredentials()
    }

    // ─── UI setup ─────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnToggle.setOnClickListener {
            saveCredentials()
            if (isServiceRunning()) {
                stopTrackingService()
            } else {
                checkAndRequestPermissions()
            }
        }

        binding.btnTest.setOnClickListener {
            saveCredentials()
            val token = binding.etBotToken.text?.toString()?.trim() ?: ""
            val chatId = binding.etChatId.text?.toString()?.trim() ?: ""
            if (token.isBlank() || chatId.isBlank()) {
                showSnackbar("Please enter your Bot Token and Chat ID first")
                return@setOnClickListener
            }
            sendTestMessage(token, chatId)
        }
    }

    private fun refreshStatus() {
        val running = isServiceRunning()

        // Dot + status label
        val dotColor = if (running) R.color.green_active else R.color.red_inactive
        binding.statusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, dotColor)
        binding.tvStatus.text = if (running) "Tracking active" else "Tracking inactive"

        // Button label
        binding.btnToggle.text = if (running) "Stop Tracking" else "Start Tracking"

        if (running) {
            // Current location
            val lat = prefs.getFloat(Prefs.KEY_CURRENT_LAT, Float.MIN_VALUE)
            val lng = prefs.getFloat(Prefs.KEY_CURRENT_LNG, Float.MIN_VALUE)
            if (lat != Float.MIN_VALUE) {
                binding.tvCurrentLocation.text =
                    "Current: ${"%.5f".format(lat)}, ${"%.5f".format(lng)}"
            } else {
                binding.tvCurrentLocation.text = "Acquiring GPS fix…"
            }

            // Distance
            val dist = prefs.getFloat(Prefs.KEY_CURRENT_DIST, -1f)
            binding.tvDistance.text = when {
                dist < 0 -> "Distance from start: —"
                dist >= 1000 -> "Distance from start: ${"%.2f".format(dist / 1000f)} km"
                else -> "Distance from start: ${"%.0f".format(dist)} m"
            }

            // Last sent
            val lastSent = prefs.getLong(Prefs.KEY_LAST_SENT_TIME, 0L)
            binding.tvLastSent.text = if (lastSent == 0L) {
                "Last Telegram message: —"
            } else {
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                "Last Telegram message: ${fmt.format(Date(lastSent))}"
            }
        } else {
            binding.tvCurrentLocation.text = "No location fix yet"
            binding.tvDistance.text = "Distance from start: —"
            binding.tvLastSent.text = "Last Telegram message: —"
        }
    }

    // ─── Credentials ─────────────────────────────────────────────────────────

    private fun saveCredentials() {
        prefs.edit()
            .putString(Prefs.KEY_BOT_TOKEN, binding.etBotToken.text?.toString()?.trim())
            .putString(Prefs.KEY_CHAT_ID, binding.etChatId.text?.toString()?.trim())
            .apply()
    }

    private fun restoreCredentials() {
        val token  = prefs.getString(Prefs.KEY_BOT_TOKEN, null)
        val chatId = prefs.getString(Prefs.KEY_CHAT_ID, null)

        // Seed the pre-configured credentials on first launch
        if (token.isNullOrBlank()) {
            prefs.edit().putString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN).apply()
        }
        if (chatId.isNullOrBlank()) {
            prefs.edit().putString(Prefs.KEY_CHAT_ID, Prefs.DEFAULT_CHAT_ID).apply()
        }

        binding.etBotToken.setText(if (token.isNullOrBlank()) Prefs.DEFAULT_BOT_TOKEN else token)
        binding.etChatId.setText(if (chatId.isNullOrBlank()) Prefs.DEFAULT_CHAT_ID else chatId)
    }

    // ─── Service control ──────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val token = binding.etBotToken.text?.toString()?.trim() ?: ""
        val chatId = binding.etChatId.text?.toString()?.trim() ?: ""
        if (token.isBlank() || chatId.isBlank()) {
            showSnackbar("Enter your Bot Token and Chat ID before starting")
            return
        }

        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            fineLocationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) {
                requestBackgroundLocation()
                return
            }
        }

        startTrackingService()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background location required")
                .setMessage(
                    "To track your location when the app is in the background, " +
                    "please choose \"Allow all the time\" on the next screen."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    backgroundLocationLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d("MainActivity", "Service start requested")
        // Clear leftover distance / timing from a previous session
        prefs.edit()
            .remove(Prefs.KEY_START_LAT)
            .remove(Prefs.KEY_START_LNG)
            .remove(Prefs.KEY_CURRENT_LAT)
            .remove(Prefs.KEY_CURRENT_LNG)
            .remove(Prefs.KEY_CURRENT_DIST)
            .remove(Prefs.KEY_LAST_SENT_TIME)
            .apply()
        handler.postDelayed({ refreshStatus() }, 500)
    }

    private fun stopTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
        stopService(intent)
        prefs.edit().putBoolean(Prefs.KEY_TRACKING_ACTIVE, false).apply()
        handler.postDelayed({ refreshStatus() }, 500)
    }

    private fun isServiceRunning(): Boolean {
        return prefs.getBoolean(Prefs.KEY_TRACKING_ACTIVE, false)
    }

    // ─── Test message ─────────────────────────────────────────────────────────

    private fun sendTestMessage(botToken: String, chatId: String) {
        binding.btnTest.isEnabled = false
        binding.btnTest.text = "Sending…"

        val lat = prefs.getFloat(Prefs.KEY_CURRENT_LAT, Float.MIN_VALUE)
        val lng = prefs.getFloat(Prefs.KEY_CURRENT_LNG, Float.MIN_VALUE)
        val latD = if (lat != Float.MIN_VALUE) lat.toDouble() else null
        val lngD = if (lng != Float.MIN_VALUE) lng.toDouble() else null

        lifecycleScope.launch {
            val message = TelegramApi.buildTestMessage(latD, lngD)
            val success = withContext(Dispatchers.IO) {
                TelegramApi.sendMessage(botToken, chatId, message)
            }
            binding.btnTest.isEnabled = true
            binding.btnTest.text = "Send Test Message"
            if (success) {
                showSnackbar("✓ Test message sent to Telegram!")
            } else {
                showSnackbar("Failed — check your Bot Token and Chat ID")
            }
        }
    }

    // ─── Dialogs & helpers ────────────────────────────────────────────────────

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location permission required")
            .setMessage(
                "GPS tracking requires location permission. " +
                "Please grant it in app settings."
            )
            .setPositiveButton("Open settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackgroundPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Background location required")
            .setMessage(
                "For tracking to work when the screen is off, open app settings " +
                "and set location permission to \"Allow all the time\"."
            )
            .setPositiveButton("Open settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
