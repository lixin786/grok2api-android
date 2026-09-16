package com.grok2api.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启。
 *
 * 为什么必须有它：一加/OPPO 的 ColorOS 在系统重启（含 OTA、手动重启）时会销毁全部进程，
 * 前台的 ApiHostService 也一并消失，且不会自动恢复——用户会发现"每次重启手机后 API 就不通了"，
 * 必须手动打开 App 点一次启动。这个接收器负责在系统启动完成后把服务拉回来。
 *
 * 是否拉起取决于持久化的用户意图（`should_run`），而不是无条件启动：
 * 用户在 App 里主动点过「停止服务」时该标志为 false，开机就不会自作主张地恢复，
 * 避免出现"我都关了它怎么又自己开了"。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // HTC/部分 ROM 用 QUICKBOOT_POWERON，一并接受。
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (!ApiHostService.shouldRun(context)) {
            Log.i(TAG, "boot received but service was stopped by user, skip")
            return
        }
        Log.i(TAG, "boot received, starting ApiHostService")
        val service = Intent(context, ApiHostService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service)
            else context.startService(service)
        }.onFailure {
            // 开机瞬间系统可能仍禁止启动前台服务（ForegroundServiceStartNotAllowedException）：
            // 退化为精确闹钟，几秒后由系统唤醒，比直接放弃可靠。
            Log.w(TAG, "start on boot failed, falling back to alarm", it)
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val pi = android.app.PendingIntent.getService(
                    context, BOOT_REQUEST,
                    Intent(context, ApiHostService::class.java).setAction(ApiHostService.ACTION_WATCHDOG),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                val at = android.os.SystemClock.elapsedRealtime() + 10_000L
                runCatching { am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                    .onFailure { am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val BOOT_REQUEST = 8790
    }
}
