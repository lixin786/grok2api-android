package com.grok2api.gateway

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地诊断报告：把运行异常 / bug **总结**成一份可读文本落盘，**只存本地、不联网**。
 *
 * 设计取舍：
 * - **只落本地**：不做任何上传。网络上报会把这个 App 变成"收集别人凭据"的管道（日志里天然有
 *   验证码/邮箱/SSO 前缀），而且本仓库是公开仓库，自动建 issue 等于把用户日志公开到互联网。
 * - **总结而非堆日志**：一次批次里 5 个号失败 3 次，有用的是"3 次分别卡在哪一步、什么错"，
 *   不是把 200KB 日志整个搬过来。所以按失败种类去重后列出来，再附一段已脱敏的日志尾部。
 * - **报告脱敏、farm.log 不脱敏**：报告是给人看/给人发的，走 [Redact] 全量脱敏；本机
 *   `files/farm.log` 保持原始，自己 `run-as` 排查要看真值。
 *
 * 两个采集点（只挂崩溃捕获会漏掉大部分问题）：
 * 1. **未捕获崩溃** — [install] 挂 `setDefaultUncaughtExceptionHandler`，崩了就写一份；
 * 2. **流程失败** — 这个 App 绝大多数错误被 `runCatching` 吞在里面（注册失败/收码失败/mint 失败/
 *    补登失败），由 [reportFlowFailures] 在批次收尾时主动总结。
 */
object DiagReporter {

    private const val DIR_NAME = "diag"
    private const val MAX_REPORTS = 20
    private const val LOG_TAIL_LINES = 60
    private const val EXPORT_MAX = 5

    private const val LOG_FILE = "farm.log"

    /** 一条失败记录（stage=卡在哪一步，kind=失败类型，detail=错误摘要）。 */
    data class Failure(val stage: String, val kind: String, val detail: String)

    @Volatile
    private var installed = false

    // ------------------------------------------------------------------ 位置

    /**
     * 诊断目录。
     *
     * 优先 App 外部私有目录（`/sdcard/Android/data/<包名>/files/diag`）——无需任何存储权限，
     * `adb pull` 直接可取；外部存储不可用时退回内部私有目录（`/data/data/<包名>/files/diag`）。
     */
    fun dir(context: Context): File {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        val d = File(base, DIR_NAME)
        runCatching { if (!d.exists()) d.mkdirs() }
        return d
    }

    /** 给 UI/日志用的一行提示：报告存在哪。 */
    fun locationHint(context: Context): String = dir(context).absolutePath

    fun list(context: Context): List<File> =
        runCatching {
            dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.name } ?: emptyList()
        }.getOrDefault(emptyList())

    fun latest(context: Context): File? = list(context).firstOrNull()

    // ------------------------------------------------------------------ 采集点 1：崩溃

    /**
     * 挂全局未捕获异常处理器。幂等，可在 Application/Activity 里重复调用。
     *
     * 崩溃路径必须**只用 applicationContext + 同步最小 I/O**：此时进程即将死掉，
     * 任何依赖 Activity/主线程回调的写法都拿不到结果。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 写好报告再让默认处理器接手（它负责弹系统崩溃框 / 杀进程）
            runCatching { writeCrash(app, thread, error) }
            prev?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable): File? {
        val stack = runCatching { error.stackTraceToString() }.getOrElse { error.toString() }
        val body = build(
            context = context,
            typeLabel = "崩溃（未捕获异常）",
            scope = "线程 ${thread.name}",
            failures = emptyList(),
            extra = "异常类型 : ${error.javaClass.name}",
            stack = stack
        )
        return write(context, "crash", body)
    }

    // ------------------------------------------------------------------ 采集点 2：流程失败

    /**
     * 批次 / 补登收尾时把本次失败**总结**成一份报告。
     *
     * @param scope     场景名，如「产号批次」「补登」
     * @param failures  本次的失败记录（同类失败已在调用方去重）
     * @return 写出的文件；无失败或写盘失败返回 null
     */
    fun reportFlowFailures(context: Context, scope: String, failures: List<Failure>): File? {
        if (failures.isEmpty()) return null
        val body = build(
            context = context,
            typeLabel = "流程失败",
            scope = scope,
            failures = failures,
            extra = null,
            stack = null
        )
        return write(context, "fail", body)
    }

    // ------------------------------------------------------------------ 报告正文

    private fun build(
        context: Context,
        typeLabel: String,
        scope: String,
        failures: List<Failure>,
        extra: String?,
        stack: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========== Grok2API 诊断报告 ==========")
        sb.appendLine("生成时间 : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        sb.appendLine("报告类型 : $typeLabel")
        sb.appendLine("场景     : $scope")
        sb.appendLine(appVersionLine(context))
        sb.appendLine("系统     : Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("机型     : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("CPU架构  : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("时区     : ${java.util.TimeZone.getDefault().id}")
        if (extra != null) sb.appendLine(extra)
        sb.appendLine()

        if (failures.isNotEmpty()) {
            sb.appendLine("----- 失败明细（按类型去重，共 ${failures.size} 类）-----")
            failures.forEachIndexed { i, f ->
                sb.appendLine("${i + 1}. 阶段=${f.stage}  类型=${f.kind}")
                sb.appendLine("   错误=${f.detail.ifBlank { "(无描述)" }}")
            }
            sb.appendLine()
        }

        sb.appendLine("----- 本机累计统计（不含账号明细）-----")
        sb.appendLine(ledgerSummary(context))
        sb.appendLine()

        if (!stack.isNullOrBlank()) {
            sb.appendLine("----- 异常堆栈 -----")
            sb.appendLine(stack.trim())
            sb.appendLine()
        }

        sb.appendLine("----- 最近日志（末 $LOG_TAIL_LINES 行）-----")
        sb.appendLine(logTail(context))
        sb.appendLine()
        sb.appendLine("说明：本报告只保存在本机，不会自动上传。内容已隐去邮箱本地部分、验证码、")
        sb.appendLine("      cookie/token/JWT 等凭据材料；完整原始日志见 files/$LOG_FILE。")
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun appVersionLine(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // versionCode 在 API 28+ 已被 longVersionCode 取代，读老字段会报 deprecation；两边都兜住
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode
        "应用版本 : ${info.versionName} (versionCode $code)"
    }.getOrElse { "应用版本 : 未知" }

    /** 只给状态计数，**不给 farm_ledger 原始行**——那些行的 error 列含上游响应体片段。 */
    private fun ledgerSummary(context: Context): String = runCatching {
        val store = NativeCore.store(context)
        val stats = store.farmLedgerStats()
        val parts = mutableListOf<String>()
        for (i in 0 until stats.length()) {
            val k = stats.names()?.optString(i).orEmpty()
            if (k.isNotEmpty()) parts += "$k=${stats.optInt(k)}"
        }
        val pending = store.farmPendingCount()
        "台账     : " + (if (parts.isEmpty()) "暂无记录" else parts.joinToString(" / ")) +
            " · 待补登=$pending"
    }.getOrElse { "台账     : 读取失败（${it.javaClass.simpleName}）" }

    private fun logTail(context: Context): String = runCatching {
        val f = File(context.filesDir, LOG_FILE)
        if (!f.exists() || f.length() == 0L) return@runCatching "(无 $LOG_FILE)"
        val lines = f.readLines()
        if (lines.isEmpty()) "(空)" else lines.takeLast(LOG_TAIL_LINES).joinToString("\n")
    }.getOrElse { "(读取失败：${it.javaClass.simpleName})" }

    // ------------------------------------------------------------------ 落盘

    private fun write(context: Context, prefix: String, body: String): File? = runCatching {
        val d = dir(context)
        val f = File(d, "$prefix-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        // 全量脱敏放在最后一步：不论上面哪个字段带进凭据，都在这一处被清掉
        f.writeText(Redact.text(body))
        prune(d)
        f
    }.getOrNull()

    /** 只保留最近 [MAX_REPORTS] 份，避免长期使用把目录堆满。 */
    private fun prune(d: File) {
        runCatching {
            val all = d.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.name } ?: return
            all.drop(MAX_REPORTS).forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------ 导出（给用户发给作者）

    /**
     * 导出内容：最近若干份报告拼成一份，便于用户一次发出来。
     *
     * 走 SAF（`ACTION_CREATE_DOCUMENT`）让用户自己选位置（一般选 Download），
     * 因为 `/Android/data/...` 在 Android 11+ 用系统文件管理器打不开。
     */
    fun exportText(context: Context): String {
        val files = list(context).take(EXPORT_MAX)
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("Grok2API 诊断报告合集（最近 ${files.size} 份，最新在前）")
        sb.appendLine("导出位置提示：原始报告单文件目录 = ${locationHint(context)}")
        sb.appendLine()
        files.forEach { f ->
            sb.appendLine(runCatching { f.readText() }.getOrDefault("(读取失败：${f.name})"))
            sb.appendLine("----------------------------------------")
        }
        return sb.toString()
    }

    /** 导出用的默认文件名。 */
    fun exportFileName(): String =
        "grok2api-diag-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
}