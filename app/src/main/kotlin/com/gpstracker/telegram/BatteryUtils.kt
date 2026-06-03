package com.gpstracker.telegram

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryInfo(
    val percent: Int,
    val isCharging: Boolean,
    val chargeType: String
) {
    fun display(): String {
        val icon = if (isCharging) "⚡" else if (percent in 0..15) "🪫" else "🔋"
        val pctStr = if (percent >= 0) "$percent%" else "?%"
        val status = if (isCharging) "Charging via $chargeType" else "Not charging"
        return "$icon $pctStr • $status"
    }
}

object BatteryUtils {
    fun getInfo(context: Context): BatteryInfo {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct    = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC       -> "AC adapter"
            BatteryManager.BATTERY_PLUGGED_USB      -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else                                    -> "Unknown"
        }
        return BatteryInfo(pct, isCharging, chargeType)
    }
}
