package com.grok2api.gateway

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时补号：池可用率 < 阈值（默认 70%）时发通知 + 触发自动批次。
 *
 * 设计取舍（与 MainActivity 的 keepalive_hour 看门狗同模式）：
 * - AlarmManager.setExactAndAllowWhileIdle 每小时自检一次；
 * - 可用率不足时：默认**发通知提醒用户来跑批次**（注册需要 WebView 可见，
 *   静默后台全自动注册受系统约束大），设置 farm_auto_batch=1 时才自动启动；
 * - 状态记录在 settings（farm_last_autotopup），避免重复触发。
 */
object FarmAutoTopUp {

    private const val CHANNEL_ID = "grok_farm_topup"
    private const val REQUEST_CODE = 3001
    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L  // 每小时

    fun threshold(context: Context): Int =
        NativeCore.store(context).getSettings().optString("farm_auto_threshold", "70").toIntOrNull() ?: 70

    fun autoBatchEnabled(context: Context): Boolean =
        NativeCore.store(context).getSettings().optString("farm_auto_batch", "0") == "1"

    /** 池可用率（%）：enabled 且不在冷却 / 总数。与概览页口径一致。 */
    fun availability(context: Context): Pair<Int, Int>? {
        val status = runCatching { NativeCore.farmStatus(context) }.getOrNull() ?: return null
        val total = status.optInt("pool_count", 0)
        if (total <= 0) return null
        // farmStatus 里有 available 计数（enabled 且不在冷却）
        val available = status.optInt("available", -1)
        if (available < 0) return null
        return available to total
    }

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, FarmTopUpReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + CHECK_INTERVAL_MS
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, FarmTopUpReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    /** 自检：低于阈值则通知（或通知里说明自动批次已排）。返回 true = 触发了补号动作。 */
    fun check(context: Context): Boolean {
        val avail = availability(context) ?: return false
        val (available, total) = avail
        val percent = available * 100 / total
        val threshold = threshold(context)
        if (percent >= threshold) return false

        val auto = autoBatchEnabled(context)
        notify(context, available, total, percent, threshold, auto)
        recordLast(context, percent, available, total)
        return auto
    }

    private fun recordLast(context: Context, percent: Int, available: Int, total: Int) {
        runCatching {
            NativeCore.store(context).saveSettings(JSONObject()
                .put("farm_last_autotopup", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date()))
                .put("farm_last_autotopup_detail", "$percent% ($available/$total)"))
        }
    }

    private fun notify(context: Context, available: Int, total: Int, percent: Int, threshold: Int, auto: Boolean) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "号池补号提醒", NotificationManager.IMPORTANCE_HIGH))
            }
            val msg = if (auto)
                "号池可用率 $percent%（$available/$total）低于 $threshold%，自动补号已就绪——打开产号页确认后自动开跑"
            else
                "号池可用率 $percent%（$available/$total）低于 $threshold%，建议补号（产号页开一批）"
            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("号池需要补号")
                .setContentText(msg)
                .setStyle(android.app.Notification.BigTextStyle().bigText(msg))
                .setContentIntent(PendingIntent.getActivity(
                    context, 0,
                    Intent(context, FarmActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build()
            nm.notify(3002, notification)
        }
    }
}

/** 每小时自检接收器。 */
class FarmTopUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { FarmAutoTopUp.check(context) }
        FarmAutoTopUp.scheduleNext(context)  // 排下一轮
    }
}