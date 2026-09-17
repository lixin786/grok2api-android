package com.grok2api.gateway

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max
import kotlin.random.Random

/** Native, process-local repository. All writes are committed before a method returns. */
class NativeStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) migrateAccountsToRegions(db)
        // v8：该配置项已并入登录流程，不再由用户配置；老库里可能残留，一并清掉
        if (oldVersion < 8) db.execSQL("DELETE FROM settings WHERE key='invite_code_domestic'")
        createSchema(db)
        ensureColumn(db, "accounts", "last_checkin_date", "TEXT")
        ensureColumn(db, "accounts", "credit_packages_json", "TEXT NOT NULL DEFAULT '[]'")
        // v9：免费档没有上游额度数字，改由本地按滚动窗口统计 + 上游确认两条来源共同支撑。
        // quota_limit_tokens 记录该账号的上限（默认取设置里的免费额度，上游确认后写入真实值）；
        // quota_confirmed_used 记录上游耗尽响应里带出的真实已用量（NULL = 尚未被上游确认过）。
        ensureColumn(db, "accounts", "quota_limit_tokens", "INTEGER")
        ensureColumn(db, "accounts", "quota_confirmed_used", "INTEGER")
        // 号池维护用的风控状态（由健康检查/失败分类写入，面板按它排序与着色）
        ensureColumn(db, "accounts", "risk_level", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "accounts", "risk_reason", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "accounts", "last_failure_code", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "accounts", "cooldown_reason", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "accounts", "next_probe_at", "REAL NOT NULL DEFAULT 0")
        ensureColumn(db, "apps", "key_enc", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "apps", "region", "TEXT NOT NULL DEFAULT 'domestic'")
        ensureColumn(db, "apps", "updated_at", "REAL NOT NULL DEFAULT 0")
        db.execSQL("UPDATE apps SET updated_at=created_at WHERE updated_at=0")
        ensureColumn(db, "usage_logs", "input_content", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "output_content", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "reasoning_content", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "credits", "REAL NOT NULL DEFAULT 0")
        ensureColumn(db, "usage_logs", "app_name", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "usage_logs", "account_region", "TEXT NOT NULL DEFAULT 'domestic'")
        // 上游收敛为单一 xAI 端点后，历史库里的 domestic/international 区分已无意义，
        // 统一归一到当前区域，避免旧备份导入后选不到号。
        db.execSQL("UPDATE accounts SET region='${AccountRegion.XAI.id}' WHERE region<>'${AccountRegion.XAI.id}'")
        db.execSQL("UPDATE apps SET region='${AccountRegion.XAI.id}' WHERE region<>'${AccountRegion.XAI.id}'")
    }

    private fun migrateAccountsToRegions(db: SQLiteDatabase) {
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='accounts'", null).use { it.moveToFirst() }
        if (!exists) return
        db.execSQL("ALTER TABLE accounts RENAME TO accounts_legacy")
        createAccountsTable(db)
        // 历史上按"国内版/国际版"分区的账号统一归到单一 xAI 区域。
        db.execSQL("""INSERT INTO accounts (
            id,uid,region,nickname,enterprise_id,domain,auth_json,enabled,priority,
            credits_remaining,credits_total,credits_expire_at,credit_packages_json,last_used_at,
            last_checkin_date,failure_count,cooldown_until,created_at,updated_at
        ) SELECT id,uid,'${AccountRegion.XAI.id}',nickname,enterprise_id,domain,auth_json,enabled,priority,
            credits_remaining,credits_total,credits_expire_at,credit_packages_json,last_used_at,
            last_checkin_date,failure_count,cooldown_until,created_at,updated_at FROM accounts_legacy""")
        db.execSQL("DROP TABLE accounts_legacy")
    }

    private fun createAccountsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uid TEXT NOT NULL, region TEXT NOT NULL DEFAULT 'domestic',
            nickname TEXT NOT NULL DEFAULT '', enterprise_id TEXT NOT NULL DEFAULT '',
            domain TEXT NOT NULL DEFAULT '', auth_json TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 0,
            credits_remaining REAL, credits_total REAL, credits_expire_at REAL,
            credit_packages_json TEXT NOT NULL DEFAULT '[]',
            quota_limit_tokens INTEGER, quota_confirmed_used INTEGER,
            last_used_at REAL NOT NULL DEFAULT 0,
            last_checkin_date TEXT, failure_count INTEGER NOT NULL DEFAULT 0,
            cooldown_until REAL NOT NULL DEFAULT 0, created_at REAL NOT NULL, updated_at REAL NOT NULL,
            UNIQUE(region, uid)
        )""")
    }

    private fun createSchema(db: SQLiteDatabase) {
        createAccountsTable(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS apps (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
            key_hash TEXT NOT NULL UNIQUE, key_prefix TEXT NOT NULL, key_enc TEXT NOT NULL DEFAULT '',
            region TEXT NOT NULL DEFAULT 'domestic',
            note TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1,
            created_at REAL NOT NULL, updated_at REAL NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS usage_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL, model TEXT NOT NULL DEFAULT '',
            protocol TEXT NOT NULL DEFAULT '', account_uid TEXT NOT NULL DEFAULT '',
            account_region TEXT NOT NULL DEFAULT 'domestic',
            input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
            total_tokens INTEGER NOT NULL DEFAULT 0, latency_ms REAL NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'ok', error TEXT NOT NULL DEFAULT '',
            input_content TEXT NOT NULL DEFAULT '', output_content TEXT NOT NULL DEFAULT '',
            reasoning_content TEXT NOT NULL DEFAULT '', credits REAL NOT NULL DEFAULT 0,
            app_name TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS model_health (
            model_id TEXT PRIMARY KEY, ok INTEGER NOT NULL DEFAULT 0,
            latency_ms INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
            checked_at REAL NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS model_cache (
            cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'dynamic',
            fetched_at REAL NOT NULL, expires_at REAL NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_accounts_pick ON accounts(enabled, cooldown_until, priority)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_ts ON usage_logs(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_model ON usage_logs(model)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_account ON usage_logs(account_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_app ON usage_logs(app_name)")
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, ddl: String) {
        val found = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var exists = false
            while (c.moveToNext()) if (c.getString(1) == column) exists = true
            exists
        }
        if (!found) db.execSQL("ALTER TABLE $table ADD COLUMN $column $ddl")
    }

    // Accounts -----------------------------------------------------------------

    fun upsertAccount(root: JSONObject, requestedRegion: String? = null): String = synchronized(lock) {
        val auth = root.optJSONObject("auth") ?: root
        val profile = root.optJSONObject("account") ?: auth.optJSONObject("account") ?: JSONObject()
        val uid = profile.optString("uid").trim()
        require(uid.isNotEmpty()) { "auth 文件缺少 account.uid" }
        require(auth.optString("accessToken").isNotBlank() || auth.optString("refreshToken").isNotBlank()) {
            "auth 文件缺少 accessToken（至少需要 accessToken 或 refreshToken）"
        }
        val region = AccountRegion.infer(requestedRegion ?: root.optString("region"), auth.optString("domain")).id
        root.put("region", region)
        val now = nowSeconds()
        val values = ContentValues().apply {
            put("uid", uid); put("region", region); put("nickname", profile.optString("nickname", uid))
            put("enterprise_id", profile.optString("enterpriseId", auth.optString("enterpriseId")))
            put("domain", auth.optString("domain")); put("auth_json", root.toString())
            put("updated_at", now)
        }
        val db = writableDatabase
        val whereArgs = arrayOf(region, uid)
        val existing = db.rawQuery("SELECT 1 FROM accounts WHERE region=? AND uid=?", whereArgs).use { it.moveToFirst() }
        if (existing) db.update("accounts", values, "region=? AND uid=?", whereArgs) else {
            values.put("created_at", now); db.insertOrThrow("accounts", null, values)
        }
        accountKey(region, uid)
    }

    fun getAccount(accountKey: String): JSONObject? = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        readableDatabase.rawQuery("SELECT * FROM accounts WHERE region=? AND uid=?", arrayOf(region, uid)).use { c ->
            if (c.moveToFirst()) accountJson(c) else null
        }
    }

    fun getAccountRoot(accountKey: String): JSONObject? = getAccount(accountKey)?.optString("auth_json")
        ?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun listAccounts(): JSONArray = synchronized(lock) {
        val out = JSONArray()
        // 面板排序：先按风控严重度（dead 置顶、warning 次之、未体检/健康在后），再按优先级。
        readableDatabase.rawQuery(
            "SELECT * FROM accounts ORDER BY " +
                "CASE risk_level WHEN 'dead' THEN 0 WHEN 'warning' THEN 1 WHEN '' THEN 2 ELSE 3 END, " +
                "priority DESC, id ASC", null
        ).use { c ->
            while (c.moveToNext()) out.put(accountJson(c).apply { remove("auth_json") })
        }
        out
    }

    /** 全部账号的 key（region:uid），顺序与列表一致；导出时按此逐个取原始 auth_json。 */
    fun accountKeys(): List<String> = synchronized(lock) {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT region, uid FROM accounts ORDER BY priority DESC,id ASC", null).use { c ->
            while (c.moveToNext()) out.add(accountKey(c.getString(0), c.getString(1)))
        }
        out
    }

    fun deleteAccount(accountKey: String): Boolean = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        writableDatabase.delete("accounts", "region=? AND uid=?", arrayOf(region, uid)) > 0
    }

    /** 写入体检结论（面板徽章/排序依据）。 */
    fun setAccountRisk(accountKey: String, level: String, reason: String,
                       failureCode: String = "", cooldownReason: String = "",
                       nextProbeAt: Double = 0.0): Boolean =
        updateAccount(accountKey, ContentValues().apply {
            put("risk_level", level)
            put("risk_reason", reason.take(300))
            put("last_failure_code", failureCode)
            put("cooldown_reason", cooldownReason)
            put("next_probe_at", nextProbeAt)
        })

    /** 只更冷却相关字段（失败分类后调用）。 */
    fun setAccountCooldown(accountKey: String, cooldownUntil: Double,
                           reason: String, failureCode: String): Boolean =
        updateAccount(accountKey, ContentValues().apply {
            put("cooldown_until", cooldownUntil)
            put("cooldown_reason", reason)
            put("last_failure_code", failureCode)
        })

    /**
     * 凭据续期的乐观写回（防 RT 轮换丢链）。
     *
     * 参照 grok2api 的做法：刷新前记下所用的 refreshToken，写回时先重读当前值，
     * **若已被别人轮换过就放弃本次写入** —— 否则会把上游已作废的旧 RT 覆盖回去，
     * 导致 refresh-token 链永久断裂（表现为账号莫名 reauthRequired）。
     * 本进程内由 store 锁保证读-比-写原子；跨进程（PC 侧同时改库）场景不适用，故仅在此做防护。
     */
    fun updateAuthIfRefreshUnchanged(accountKey: String, expectedRefreshToken: String,
                                     newAuthJson: String): Boolean = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        val db = writableDatabase
        db.beginTransaction()
        try {
            var current = ""
            db.rawQuery("SELECT auth_json FROM accounts WHERE region=? AND uid=?", arrayOf(region, uid)).use { c ->
                if (c.moveToFirst()) current = c.getString(0) ?: ""
            }
            if (current.isBlank()) return@synchronized false
            val curRt = runCatching {
                JSONObject(current).optJSONObject("auth")?.optString("refreshToken").orEmpty()
            }.getOrDefault("")
            if (expectedRefreshToken.isNotBlank() && curRt != expectedRefreshToken) {
                // 已被其他路径轮换过：本次结果作废，保留库里的新值
                return@synchronized false
            }
            val ok = db.update("accounts", ContentValues().apply {
                put("auth_json", newAuthJson)
                put("updated_at", nowSeconds())
            }, "region=? AND uid=?", arrayOf(region, uid)) > 0
            db.setTransactionSuccessful()
            ok
        } finally {
            db.endTransaction()
        }
    }

    fun setAccountEnabled(accountKey: String, enabled: Boolean): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("enabled", if (enabled) 1 else 0); put("updated_at", nowSeconds())
    })

    /** 设置账号优先级：0 = 不额外加权。选号权重用 max(0, priority)，所以负数必须在这里夹紧，避免写进"改小了却没生效"的假值。 */
    fun setAccountPriority(accountKey: String, priority: Int): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("priority", priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY)); put("updated_at", nowSeconds())
    })

    fun clearAccountCooldown(accountKey: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("failure_count", 0); put("cooldown_until", 0); put("updated_at", nowSeconds())
    })

    fun markAccountSuccess(accountKey: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("failure_count", 0); put("cooldown_until", 0); put("last_used_at", nowSeconds()); put("updated_at", nowSeconds())
    })

    fun markAccountFailure(accountKey: String, cooldownSeconds: Long): Boolean = synchronized(lock) {
        val current = getAccount(accountKey) ?: return@synchronized false
        updateAccount(accountKey, ContentValues().apply {
            put("failure_count", current.optInt("failure_count") + 1)
            put("cooldown_until", nowSeconds() + max(0L, cooldownSeconds)); put("updated_at", nowSeconds())
        })
    }

    /** 记录上游确认的真实额度（耗尽响应里带出的 actual/limit）。 */
    fun setConfirmedQuota(accountKey: String, usedTokens: Long, limitTokens: Long): Boolean = updateAccount(accountKey, ContentValues().apply {
        if (usedTokens >= 0) put("quota_confirmed_used", usedTokens)
        if (limitTokens > 0) put("quota_limit_tokens", limitTokens)
        put("updated_at", nowSeconds())
    })

    /** 清空上游确认值：滚动窗口重置、成功请求后调用，让本地统计重新接管。 */
    fun clearConfirmedQuota(accountKey: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        putNull("quota_confirmed_used"); put("updated_at", nowSeconds())
    })

    fun setAccountCredits(accountKey: String, remaining: Double?, total: Double?, expireAt: Double? = null, packages: JSONArray = JSONArray()): Boolean =
        updateAccount(accountKey, ContentValues().apply {
            if (remaining == null) putNull("credits_remaining") else put("credits_remaining", remaining)
            if (total == null) putNull("credits_total") else put("credits_total", total)
            if (expireAt == null) putNull("credits_expire_at") else put("credits_expire_at", expireAt)
            put("credit_packages_json", packages.toString()); put("updated_at", nowSeconds())
        })

    /** 额度未知（上游未返回数字）：把额度字段全部置空，UI 显示"尚未刷新"。 */
    fun clearAccountCredits(accountKey: String): Boolean = setAccountCredits(accountKey, null, null, null, JSONArray())

    fun setCheckinDate(accountKey: String, date: String): Boolean = updateAccount(accountKey, ContentValues().apply {
        put("last_checkin_date", date); put("updated_at", nowSeconds())
    })

    private fun updateAccount(accountKey: String, values: ContentValues): Boolean = synchronized(lock) {
        val (region, uid) = splitAccountKey(accountKey)
        writableDatabase.update("accounts", values, "region=? AND uid=?", arrayOf(region, uid)) > 0
    }

    /**
     * 选号：**严格轮询**（round-robin）。
     *
     * 规则：在「已启用且不在冷却期」的账号里，永远挑 `last_used_at` 最小的那个（最久未使用），
     * 选中后立刻把它顶到最新——顺序天然形成 A→B→C→D→A… 的确定性轮询。
     * 冷却中的账号自动跳过，冷却结束自然回到轮询队列。
     *
     * 为什么不用加权随机：随机分配在样本少时会长时间偏向某几个号（实测 10 次里 6 次打同一个），
     * 导致单号被提前用尽而其余号闲置——用户观感就是"注册了账号也不管用"。
     * 轮询保证每个号被均匀使用，额度消耗可预期。
     *
     * 候选条件**只看「启用 + 不在冷却」**，不看 `credits_remaining`：
     * xAI 免费档 `/billing` 不返回额度数字，该字段对免费号无意义；
     * 早先把它当硬门槛（`IS NULL OR > 0`）曾导致整池账号被排除、退化成"永远选 id 最小的一个"。
     */
    fun selectAccount(markUsed: Boolean = true, region: String? = null): JSONObject? = synchronized(lock) {
        val now = nowSeconds()
        val regionClause = if (region.isNullOrBlank()) "" else " AND region=?"
        val regionArgs = if (region.isNullOrBlank()) null else arrayOf(region)
        var picked: JSONObject? = null
        // 最久未用者优先；并列时按 id 保证顺序稳定（否则同一秒内选号会随机漂移）。
        // 注：bot_risk 是逐行算 JWT 的代价较高，不能放进 SQL 排序；改为取出池后两级排序——
        // 正常号按 last_used_at 轮询，被上游打过 bot 标记的号整体排到最后（仍可用但降权）。
        val pool = mutableListOf<JSONObject>()
        readableDatabase.rawQuery(
            "SELECT * FROM accounts WHERE enabled=1 AND cooldown_until<=?$regionClause ORDER BY last_used_at ASC, id ASC",
            if (regionArgs == null) arrayOf(now.toString()) else arrayOf(now.toString(), *regionArgs)
        ).use { c ->
            while (c.moveToNext()) pool += accountJson(c)
        }
        if (pool.isEmpty()) return@synchronized null
        picked = pool.firstOrNull { it.optInt("bot_risk") == 0 } ?: pool.first()
        val selected = picked ?: return@synchronized null
        if (markUsed) {
            updateAccount(selected.getString("account_key"), ContentValues().apply {
                put("last_used_at", now); put("updated_at", now)
            })
        }
        selected
    }

    // Apps/API keys -------------------------------------------------------------

    fun createApp(name: String, note: String = "", region: String = AccountRegion.XAI.id, customKey: String? = null): JSONObject = synchronized(lock) {
        val normalizedName = validateAppName(name)
        val normalizedNote = validateAppNote(note)
        val normalizedRegion = AccountRegion.fromStrict(region).id
        val key = customKey?.let(::validateAppKey) ?: "sk-" + randomHex(24)
        val now = nowSeconds()
        val values = ContentValues().apply {
            put("name", normalizedName); put("key_hash", sha256(key)); put("key_prefix", keyPrefix(key))
            put("key_enc", encrypt(key)); put("region", normalizedRegion); put("note", normalizedNote); put("enabled", 1)
            put("created_at", now); put("updated_at", now)
        }
        val id = insertApp(values)
        JSONObject().put("id", id).put("name", normalizedName).put("key", key).put("key_prefix", values.getAsString("key_prefix"))
            .put("note", normalizedNote).put("enabled", true).put("region", normalizedRegion).put("region_label", AccountRegion.from(normalizedRegion).label)
    }

    fun updateApp(appId: Long, name: String, note: String, region: String, replacementKey: String? = null): JSONObject = synchronized(lock) {
        val normalizedName = validateAppName(name)
        val normalizedNote = validateAppNote(note)
        val normalizedRegion = AccountRegion.fromStrict(region).id
        require(readableDatabase.rawQuery("SELECT 1 FROM apps WHERE id=?", arrayOf(appId.toString())).use { it.moveToFirst() }) { "API Key 不存在" }
        val values = ContentValues().apply {
            put("name", normalizedName); put("note", normalizedNote); put("region", normalizedRegion); put("updated_at", nowSeconds())
            replacementKey?.let(::validateAppKey)?.let { key ->
                put("key_hash", sha256(key)); put("key_prefix", keyPrefix(key)); put("key_enc", encrypt(key))
            }
        }
        try {
            require(writableDatabase.update("apps", values, "id=?", arrayOf(appId.toString())) > 0) { "API Key 更新失败" }
        } catch (error: android.database.sqlite.SQLiteConstraintException) {
            throw IllegalArgumentException(if (replacementKey != null) "应用名称或 API Key 已存在" else "应用名称已存在", error)
        }
        readableDatabase.rawQuery("SELECT * FROM apps WHERE id=?", arrayOf(appId.toString())).use { cursor ->
            require(cursor.moveToFirst()) { "API Key 不存在" }
            return@synchronized appJson(cursor).apply { replacementKey?.let { put("key", it) } }
        }
    }

    fun ensureDefaultApp(legacyKey: String? = null): JSONObject = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM apps ORDER BY id LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                val row = appJson(c)
                var key = decrypt(c.string("key_enc"))
                if (key.isBlank() && !legacyKey.isNullOrBlank() &&
                    MessageDigest.isEqual(sha256(legacyKey).toByteArray(), c.string("key_hash").toByteArray())) {
                    writableDatabase.update("apps", ContentValues().apply { put("key_enc", encrypt(legacyKey)) },
                        "id=?", arrayOf(c.getLong(c.getColumnIndexOrThrow("id")).toString()))
                    key = legacyKey
                }
                row.put("key", key)
                return@synchronized row
            }
        }
        if (legacyKey.isNullOrBlank()) return@synchronized createApp("Default", "系统默认应用")
        val values = ContentValues().apply {
            put("name", "Default"); put("key_hash", sha256(legacyKey)); put("key_prefix", keyPrefix(legacyKey))
            put("key_enc", encrypt(legacyKey)); put("note", "从旧版迁移"); put("enabled", 1); put("created_at", nowSeconds()); put("updated_at", nowSeconds())
        }
        val id = writableDatabase.insertOrThrow("apps", null, values)
        JSONObject().put("id", id).put("name", "Default").put("key", legacyKey)
    }

    fun listApps(): JSONArray = synchronized(lock) {
        val out = JSONArray()
        readableDatabase.rawQuery("""SELECT a.id,a.name,a.key_prefix,a.region,a.note,a.enabled,a.created_at,a.updated_at,
            COUNT(u.id) requests,COALESCE(SUM(u.total_tokens),0) tokens,COALESCE(SUM(u.credits),0) credits
            FROM apps a LEFT JOIN usage_logs u ON u.app_name=a.name GROUP BY a.id ORDER BY a.id""", null).use { c ->
            while (c.moveToNext()) out.put(appJson(c))
        }
        out
    }

    fun getAppKey(appId: Long): String? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT key_enc FROM apps WHERE id=?", arrayOf(appId.toString())).use { c ->
            if (!c.moveToFirst()) null else decrypt(c.getString(0)).ifBlank { null }
        }
    }

    fun authenticateApp(key: String): JSONObject? = synchronized(lock) {
        if (key.isBlank()) return@synchronized null
        val hash = sha256(key)
        readableDatabase.rawQuery("SELECT * FROM apps WHERE key_hash=? AND enabled=1", arrayOf(hash)).use { c ->
            if (c.moveToFirst() && MessageDigest.isEqual(hash.toByteArray(), c.string("key_hash").toByteArray())) appJson(c).apply { remove("key_enc") } else null
        }
    }

    fun setAppEnabled(appId: Long, enabled: Boolean): Boolean = synchronized(lock) {
        writableDatabase.update("apps", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(appId.toString())) > 0
    }

    fun toggleApp(appId: Long): Boolean? = synchronized(lock) {
        val enabled = readableDatabase.rawQuery("SELECT enabled FROM apps WHERE id=?", arrayOf(appId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else return@synchronized null
        }
        setAppEnabled(appId, !enabled); !enabled
    }

    fun deleteApp(appId: Long): Boolean = synchronized(lock) {
        writableDatabase.delete("apps", "id=?", arrayOf(appId.toString())) > 0
    }

    // Usage --------------------------------------------------------------------

    fun logUsage(record: JSONObject): Long = synchronized(lock) {
        val input = record.optLong("input_tokens", record.optLong("prompt_tokens", 0))
        val output = record.optLong("output_tokens", record.optLong("completion_tokens", 0))
        // JSONObject.optDouble 在字段缺失时返回 NaN，而 SQLite 会把 NaN 存成 NULL，
        // 直接触发 usage_logs 的 NOT NULL 约束（credits 曾因此让所有记录写入失败、记录页永远空白）。
        // 统一兜底：非有限值一律回落到默认值。
        fun real(key: String, fallback: Double): Double = record.optDouble(key, fallback).takeIf { it.isFinite() } ?: fallback
        writableDatabase.insertOrThrow("usage_logs", null, ContentValues().apply {
            put("ts", real("ts", nowSeconds())); put("model", record.optString("model"))
            put("protocol", record.optString("protocol", "chat")); put("account_uid", record.optString("account_uid"))
            put("account_region", record.optString("account_region", "domestic"))
            put("input_tokens", input); put("output_tokens", output); put("total_tokens", record.optLong("total_tokens", input + output))
            put("latency_ms", real("latency_ms", 0.0)); put("status", record.optString("status", "ok")); put("error", record.optString("error"))
            // 写入即截断：这三个字段是数据库体积的唯一主要来源。长对话（多轮历史累积）
            // 与长思考链单条可达数百 KB，若不设限，几条请求就能让库膨胀到几十 MB。
            // 上限内的内容仍完整保留（供排查与统计），超长部分裁掉并标注原文长度。
            put("input_content", clip(record.optString("input_content"), INPUT_LIMIT))
            put("output_content", clip(record.optString("output_content"), OUTPUT_LIMIT))
            put("reasoning_content", clip(record.optString("reasoning_content"), REASONING_LIMIT))
            put("credits", real("credits", 0.0)); put("app_name", record.optString("app_name"))
        })
    }

    fun usageSummary(): JSONObject = synchronized(lock) {
        val total = aggregate("SELECT COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs")
        val today = aggregate("SELECT COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs WHERE ts>=?", arrayOf(localMidnight().toString()))
        JSONObject().put("total_requests", total.first).put("total_tokens", total.second)
            .put("today_requests", today.first).put("today_tokens", today.second)
            .put("by_protocol", grouped("protocol", "protocol")).put("by_model", grouped("model", "model", 20))
            .put("by_app", grouped("COALESCE(NULLIF(app_name,''),'(未命名)')", "app"))
    }

    fun usageRecent(page: Int = 1, pageSize: Int = 20, protocol: String? = null, model: String? = null,
                    appName: String? = null, status: String? = null, light: Boolean = true): JSONObject = synchronized(lock) {
        val filter = usageFilter(protocol, model, appName, status)
        val size = pageSize.coerceIn(1, 500); val safePage = max(1, page)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM usage_logs ${filter.first}", filter.second.toTypedArray()).use { c -> c.moveToFirst(); c.getInt(0) }
        val columns = if (light) "id,ts,model,protocol,account_uid,input_tokens,output_tokens,total_tokens,latency_ms,status,error,credits,app_name" else "*"
        val args = filter.second + listOf(size.toString(), ((safePage - 1) * size).toString())
        val rows = JSONArray()
        readableDatabase.rawQuery("SELECT $columns FROM usage_logs ${filter.first} ORDER BY id DESC LIMIT ? OFFSET ?", args.toTypedArray()).use { c ->
            while (c.moveToNext()) rows.put(rowJson(c))
        }
        JSONObject().put("records", rows).put("total", count).put("page", safePage).put("page_size", size)
    }

    fun getUsage(id: Long): JSONObject? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM usage_logs WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) rowJson(c) else null }
    }

    fun usageFilters(): JSONObject = synchronized(lock) {
        val currentApps = distinct("SELECT name FROM apps ORDER BY id")
        val usedApps = distinct("SELECT DISTINCT app_name FROM usage_logs WHERE app_name!='' ORDER BY app_name")
        val current = (0 until currentApps.length()).map { currentApps.getString(it) }.toSet()
        val history = JSONArray(); for (i in 0 until usedApps.length()) if (usedApps.getString(i) !in current) history.put(usedApps.getString(i))
        val unnamed = readableDatabase.rawQuery("SELECT 1 FROM usage_logs WHERE app_name='' LIMIT 1", null).use { it.moveToFirst() }
        JSONObject().put("protocols", distinct("SELECT DISTINCT protocol FROM usage_logs WHERE protocol!='' ORDER BY protocol"))
            .put("models", distinct("SELECT DISTINCT model FROM usage_logs WHERE model!='' ORDER BY model"))
            .put("apps", currentApps).put("apps_history", history).put("has_unnamed", unnamed)
            .put("statuses", distinct("SELECT DISTINCT status FROM usage_logs WHERE status!='' ORDER BY status"))
    }

    fun usageTimeseries(granularity: String = "hour", points: Int = 24, model: String? = null): JSONArray = synchronized(lock) {
        val day = granularity == "day"; val bucket = if (day) 86400L else 3600L
        val count = points.coerceIn(1, 1000); val now = nowSeconds().toLong()
        val end = if (day) localMidnight().toLong() else now - now % bucket
        val start = end - (count - 1) * bucket
        val sql = if (day) {
            "SELECT strftime('%Y-%m-%d', ts, 'unixepoch', 'localtime') b,COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs WHERE ts>=? AND ts<?"
        } else {
            "SELECT (CAST(ts AS INTEGER)/$bucket)*$bucket b,COUNT(*) c,COALESCE(SUM(total_tokens),0) t FROM usage_logs WHERE ts>=? AND ts<?"
        }
        val query = StringBuilder(sql)
        val args = mutableListOf(start.toString(), (end + bucket).toString())
        if (!model.isNullOrBlank()) { query.append(" AND model=?"); args += model }
        query.append(" GROUP BY b")
        val found = mutableMapOf<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(query.toString(), args.toTypedArray()).use { c -> while (c.moveToNext()) found[c.getString(0)] = c.getLong(1) to c.getLong(2) }
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val labelFormat = SimpleDateFormat(if (day) "MM-dd" else "MM-dd HH:mm", Locale.getDefault())
        JSONArray().apply { for (i in 0 until count) { val ts = start + i * bucket; val key = if (day) keyFormat.format(Date(ts * 1000)) else ts.toString(); val v = found[key] ?: (0L to 0L); put(JSONObject().put("bucket_ts", ts).put("bucket", labelFormat.format(Date(ts * 1000))).put("count", v.first).put("tokens", v.second)) } }
    }

    fun trimUsageContent(inputLimit: Int = INPUT_LIMIT, outputLimit: Int = OUTPUT_LIMIT, reasoningLimit: Int = REASONING_LIMIT): Int = synchronized(lock) {
        var changed = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id,input_content,output_content,reasoning_content FROM usage_logs WHERE LENGTH(input_content)>? OR LENGTH(output_content)>? OR LENGTH(reasoning_content)>?", arrayOf(inputLimit.toString(), outputLimit.toString(), reasoningLimit.toString())).use { c ->
                while (c.moveToNext()) {
                    db.update("usage_logs", ContentValues().apply { put("input_content", clip(c.getString(1), inputLimit)); put("output_content", clip(c.getString(2), outputLimit)); put("reasoning_content", clip(c.getString(3), reasoningLimit)) }, "id=?", arrayOf(c.getLong(0).toString())); changed++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed
    }

    /**
     * 按账号聚合滚动窗口内的 token 用量。
     *
     * `usage_logs.account_uid` 存的是**裸 uid**（不带 region 前缀），与 `accounts.uid` 可直接 join。
     * 用 LEFT JOIN 保证「没有任何调用记录」的账号也会返回一行（tokens=0），
     * 否则新登录的账号会在余额页里凭空消失。
     *
     * 同时返回窗口内最早一条记录的时间：免费额度是 24 小时滚动窗口，
     * 「最早一条 + 窗口长度」就是该账号预计重置的时刻。
     */
    fun accountTokenUsage(windowSeconds: Double): JSONArray = synchronized(lock) {
        val out = JSONArray()
        val since = nowSeconds() - windowSeconds
        readableDatabase.rawQuery(
            """SELECT a.uid, a.nickname, a.enabled, a.cooldown_until,
                      a.quota_limit_tokens, a.quota_confirmed_used,
                      COALESCE(SUM(l.total_tokens),0) tokens,
                      COUNT(l.id) requests,
                      MIN(l.ts) first_ts
               FROM accounts a
               LEFT JOIN usage_logs l ON l.account_uid = a.uid AND l.ts >= ?
               GROUP BY a.id
               ORDER BY a.priority DESC, a.id ASC""",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().apply {
                    put("uid", c.getString(0)); put("nickname", c.getString(1))
                    put("enabled", c.getInt(2) != 0); put("cooldown_until", c.getDouble(3))
                    if (c.isNull(4)) put("quota_limit_tokens", JSONObject.NULL) else put("quota_limit_tokens", c.getLong(4))
                    if (c.isNull(5)) put("quota_confirmed_used", JSONObject.NULL) else put("quota_confirmed_used", c.getLong(5))
                    put("window_tokens", c.getLong(6)); put("requests", c.getLong(7))
                    if (c.isNull(8)) put("first_ts", JSONObject.NULL) else put("first_ts", c.getDouble(8))
                })
            }
        }
        out
    }

    /** 保存一个模型的健康探测结果（手动触发，持久化以便重启后仍显示）。 */
    fun saveModelHealth(modelId: String, ok: Boolean, latencyMs: Long, error: String): Boolean = synchronized(lock) {
        runCatching {
            writableDatabase.insertWithOnConflict("model_health", null, ContentValues().apply {
                put("model_id", modelId); put("ok", if (ok) 1 else 0)
                put("latency_ms", latencyMs); put("error", error)
                put("checked_at", nowSeconds())
            }, SQLiteDatabase.CONFLICT_REPLACE) > 0
        }.getOrDefault(false)
    }

    /** 读取全部模型健康结果（key = model_id）。表缺失等异常时返回空 map，绝不让调用方崩。 */
    fun loadModelHealth(): Map<String, JSONObject> = synchronized(lock) {
        val out = LinkedHashMap<String, JSONObject>()
        runCatching {
            readableDatabase.rawQuery("SELECT model_id, ok, latency_ms, error, checked_at FROM model_health", null).use { c ->
                while (c.moveToNext()) {
                    out[c.getString(0)] = JSONObject().put("done", true).put("ok", c.getInt(1) != 0)
                        .put("latency_ms", c.getLong(2)).put("error", c.getString(3))
                        .put("checked_at", c.getDouble(4))
                }
            }
        }.onFailure { android.util.Log.w("NativeStore", "loadModelHealth failed (table missing?)", it) }
        out
    }

    fun cleanupUsage(retentionDays: Int): Int = synchronized(lock) {
        val cutoff = nowSeconds() - max(0, retentionDays) * 86400.0
        writableDatabase.delete("usage_logs", "ts<?", arrayOf(cutoff.toString()))
    }

    /**
     * 存储体积诊断：返回数据库文件大小 + 各内容字段占用估算 + 记录条数。
     *
     * 之前存储占用在界面上完全不可见，用户只能从系统设置里看到"数据 200MB"却不知从何而来，
     * 也没法判断某次清理到底有没有效果。这里把体积拆开，让"谁在占空间"一目了然。
     */
    fun storageInfo(): JSONObject = synchronized(lock) {
        val db = writableDatabase
        val pageCount = db.rawQuery("PRAGMA page_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val fileBytes = pageCount * pageSize
        // 分别统计三个内容字段的总字符数，用于判断体积主要来自输入、输出还是思考链。
        val contentBytes = db.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(input_content)),0),COALESCE(SUM(LENGTH(output_content)),0),COALESCE(SUM(LENGTH(reasoning_content)),0),COUNT(*) FROM usage_logs", null
        ).use { c ->
            if (c.moveToFirst()) longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else longArrayOf(0, 0, 0, 0)
        }
        // 空闲页 = 曾经写入又被删掉的空间。SQLite 不会自动归还给系统，需要 VACUUM 才能真正缩小文件。
        val freePages = db.rawQuery("PRAGMA freelist_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        JSONObject()
            .put("db_bytes", fileBytes)
            .put("free_bytes", freePages * pageSize)
            .put("rows", contentBytes[3])
            .put("input_bytes", contentBytes[0])
            .put("output_bytes", contentBytes[1])
            .put("reasoning_bytes", contentBytes[2])
    }

    /** 回收空闲页，把数据库文件真正缩小（VACUUM 不能在事务中执行）。 */
    fun vacuum(): Unit = synchronized(lock) {
        runCatching { writableDatabase.execSQL("VACUUM") }
    }

    /** 删除单条使用记录；返回是否真的命中了行（未命中说明记录已被清掉，UI 不该提示“删除成功”）。 */
    fun deleteUsage(id: Long): Boolean = synchronized(lock) {
        writableDatabase.delete("usage_logs", "id=?", arrayOf(id.toString())) > 0
    }

    /**
     * 清空全部使用记录，返回删除条数。
     * 记录里存的是完整对话原文，删空后文件不会自动缩小，故默认 VACUUM 回收空间；
     * VACUUM 不允许在事务里执行，这里刻意放在 delete 之后单独调用。
     */
    fun clearUsage(vacuum: Boolean = true): Int = synchronized(lock) {
        val db = writableDatabase
        val removed = db.delete("usage_logs", null, null)
        if (removed > 0 && vacuum) runCatching { db.execSQL("VACUUM") }
        removed
    }

    // Settings and model cache --------------------------------------------------

    fun getSettings(): JSONObject = synchronized(lock) {
        val out = JSONObject(DEFAULT_SETTINGS)
        readableDatabase.rawQuery("SELECT key,value FROM settings", null).use { c -> while (c.moveToNext()) out.put(c.getString(0), c.getString(1)) }
        out
    }

    fun saveSettings(values: JSONObject): JSONObject = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            values.keys().forEach { key -> if (key in DEFAULT_SETTINGS) db.insertWithOnConflict("settings", null, ContentValues().apply { put("key", key); put("value", values.optString(key)) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        getSettings()
    }

    fun saveModelCache(models: JSONArray, source: String = "dynamic", ttlSeconds: Long = 3600, region: String = "domestic"): Unit = synchronized(lock) {
        val now = nowSeconds()
        writableDatabase.insertWithOnConflict("model_cache", null, ContentValues().apply {
            put("cache_key", modelCacheKey(region)); put("payload", models.toString()); put("source", source)
            put("fetched_at", now); put("expires_at", now + max(1L, ttlSeconds))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // 旧版缓存条目里没有 credits（成本倍率）等字段，写完新格式后清掉旧键，避免后续读到残缺目录
        writableDatabase.delete("model_cache", "cache_key=?", arrayOf("models:$region"))
    }

    fun getModelCache(allowExpired: Boolean = true, region: String = "domestic"): JSONObject? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT payload,source,fetched_at,expires_at FROM model_cache WHERE cache_key=?", arrayOf(modelCacheKey(region))).use { c ->
            if (!c.moveToFirst() || (!allowExpired && c.getDouble(3) < nowSeconds())) null else JSONObject()
                .put("models", runCatching { JSONArray(c.getString(0)) }.getOrDefault(JSONArray()))
                .put("source", c.getString(1)).put("fetched_at", c.getDouble(2)).put("expires_at", c.getDouble(3))
        }
    }

    /** 模型缓存键带格式版本：目录结构升级（如新增 credits 成本倍率）后旧缓存自动失效，避免 UI 读到残缺字段。 */
    /** 缓存键带版本号：模型目录结构变化（如全量目录、auto 倍率修复）时递增，确保升级后不会命中旧缓存。 */
    private fun modelCacheKey(region: String): String = "models:v3:$region"

    private fun usageFilter(protocol: String?, model: String?, appName: String?, status: String?): Pair<String, MutableList<String>> {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (!protocol.isNullOrBlank()) { clauses += "protocol=?"; args += protocol }
        if (!model.isNullOrBlank()) { clauses += "model=?"; args += model }
        if (appName != null) { clauses += "COALESCE(app_name,'')=?"; args += appName }
        if (!status.isNullOrBlank()) { clauses += "status=?"; args += status }
        return (if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}") to args
    }

    private fun aggregate(sql: String, args: Array<String>? = null): Pair<Long, Long> = readableDatabase.rawQuery(sql, args).use { c -> c.moveToFirst(); c.getLong(0) to c.getLong(1) }
    private fun grouped(expression: String, alias: String, limit: Int? = null): JSONArray {
        val out = JSONArray(); val suffix = if (limit == null) "" else " LIMIT $limit"
        // 同时汇总 credits：用量页的分布维度要能显示真实积分消耗（历史上恒为 0，见 ApiHostService.upstreamCredits）。
        readableDatabase.rawQuery("SELECT $expression k,COUNT(*) c,COALESCE(SUM(total_tokens),0) t,COALESCE(SUM(credits),0) cr FROM usage_logs GROUP BY k ORDER BY c DESC$suffix", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put(alias, c.getString(0)).put("count", c.getLong(1)).put("tokens", c.getLong(2)).put("credits", c.getDouble(3)))
        }; return out
    }
    private fun distinct(sql: String): JSONArray = JSONArray().apply { readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) put(c.getString(0)) } }
    private fun accountJson(c: Cursor): JSONObject = rowJson(c).apply {
        val region = optString("region", "domestic")
        put("account_key", accountKey(region, optString("uid")))
        put("region_label", AccountRegion.from(region).label)
        // 健康 = 启用且不在冷却期。额度字段对免费账号无意义（上游不返回），
        // 不再拿它判定健康，否则会整池显示"冷却"。
        put("enabled", optInt("enabled") != 0); put("healthy", optBoolean("enabled") && optDouble("cooldown_until") <= nowSeconds())
        // 风控标记：xAI 在 access_token 的 JWT 里放 bot_flag_source / bfs claim，
        // 值为数字 1 或 2 表示账号被上游打上 bot 风险标记（官方 grok2api 同款判定）。
        // 被标记的号仍可用，但随时可能被拒——UI 要显式提示，且轮询时降低其优先级。
        val jwt = runCatching {
            val auth = optString("auth_json")
            val token = runCatching { JSONObject(auth) }.getOrNull()
                ?.optJSONObject("auth")?.optString("accessToken").orEmpty()
            botRiskSourceFromToken(token)
        }.getOrDefault(0)
        put("bot_risk", jwt)
        // 面板用的风控状态：risk_level 为 '' 表示尚未体检（UI 显示"未体检"而不是"健康"）
        put("risk_level", optString("risk_level"))
        put("risk_reason", optString("risk_reason"))
        put("last_failure_code", optString("last_failure_code"))
        put("cooldown_reason", optString("cooldown_reason"))
        put("next_probe_at", optDouble("next_probe_at", 0.0))
        put("cooldown_remaining_sec", maxOf(0.0, optDouble("cooldown_until") - nowSeconds()))
        put("credit_packages", runCatching { JSONArray(optString("credit_packages_json")) }.getOrDefault(JSONArray())); remove("credit_packages_json")
    }
    private fun accountKey(region: String, uid: String): String = "$region:$uid"

    /**
     * 从 access_token 的 JWT payload 里提取 bot 风险标记。
     * 只认 JSON 数字 1/2（字符串 "1" 不算，官方同款规则）；取不到返回 0 = 无标记。
     */
    private fun botRiskSourceFromToken(token: String): Int {
        if (token.isBlank()) return 0
        val parts = token.split('.')
        if (parts.size < 2) return 0
        val claims = runCatching {
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8))
        }.getOrNull() ?: return 0
        val primary = claims.opt("bot_flag_source")
        val fallback = claims.opt("bfs")
        fun asSource(v: Any?): Int = (v as? Number)?.toInt()?.takeIf { it == 1 || it == 2 } ?: 0
        return asSource(primary).takeIf { it != 0 } ?: asSource(fallback)
    }
    private fun splitAccountKey(value: String): Pair<String, String> {
        val split = value.indexOf(':')
        return if (split > 0) value.substring(0, split) to value.substring(split + 1) else "domestic" to value
    }
    private fun appJson(c: Cursor): JSONObject = rowJson(c).apply {
        val region = optString("region", AccountRegion.XAI.id)
        put("region", region); put("region_label", AccountRegion.from(region).label)
        put("enabled", optInt("enabled") != 0)
    }
    private fun validateAppName(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "请输入应用名称" }
        require(it.length <= 40) { "应用名称不能超过 40 个字符" }
    }
    private fun validateAppNote(value: String): String = value.trim().also {
        require(it.length <= 200) { "备注不能超过 200 个字符" }
    }
    private fun validateAppKey(value: String): String = value.also {
        require(it.length in 16..256) { "自定义 Key 需要 16–256 位字符" }
        require(it.all { ch -> ch.code in 33..126 }) { "自定义 Key 只能包含无空格的可打印 ASCII 字符" }
    }
    private fun keyPrefix(key: String): String = if (key.length <= 8) "${key.take(3)}•••••" else "${key.take(8)}…"
    private fun insertApp(values: ContentValues): Long = try {
        writableDatabase.insertOrThrow("apps", null, values)
    } catch (error: android.database.sqlite.SQLiteConstraintException) {
        throw IllegalArgumentException("应用名称或 API Key 已存在", error)
    }
    private fun rowJson(c: Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until c.columnCount) put(c.getColumnName(i), when (c.getType(i)) { Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i); Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP); else -> c.getString(i) })
    }
    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""
    private fun clip(value: String?, limit: Int): String { val v = value.orEmpty(); return if (v.length <= limit) v else v.take(limit) + "\n…（已截断，原文 ${v.length} 字符）" }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, keyStoreKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String = runCatching {
        if (encoded.isBlank()) return ""
        val raw = Base64.decode(encoded, Base64.NO_WRAP); val iv = raw.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, keyStoreKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }.getOrDefault("")
    private fun keyStoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    companion object {
        const val DATABASE_NAME = "grok-native.db"
        // v10：新增 model_health 表。注意每次改 schema 必须同步 bump 此版本号——
        // 否则覆盖安装时 SQLite 不会回调 onCreate/onUpgrade，新表建不出来，
        // 运行期查询直接抛 no such table（v1.2.1 真实踩坑：刷新模型整个失败）。
        // v11：新增号池风控状态列（risk_level/risk_reason/last_failure_code/
        // cooldown_reason/next_probe_at）——面板徽章、异常置顶与恢复探测都依赖它们。
        // 注意：新增 ensureColumn 必须同时升这个版本号，否则老库不会执行迁移（踩过）。
        private const val DATABASE_VERSION = 11
        private const val KEY_ALIAS = "grok_native_app_keys"
        /** 优先级下限：0 = 不额外加权（选号权重 = 1.0）。 */
        const val MIN_PRIORITY = 0
        /** 优先级上限：兜底防止误写极端值，让加权随机退化成固定选号。 */
        const val MAX_PRIORITY = 999
        /** saveSettings 只接受本表内的 key，新增配置项必须同时加到 here 与设置页控件。 */
        private val DEFAULT_SETTINGS = mapOf("checkin_hours" to "9,21", "credit_refresh_min" to "30", "model_refresh_hour" to "6", "model_ttl_min" to "60", "aa_refresh_hour" to "7", "keepalive_hour" to "22", "keepalive_enabled" to "1", "aa_api_key" to "", "usage_retention_days" to "30", "free_token_limit" to "500000",
            // 号池风控 / 农场（PC 侧自动注册管道与本地体检共用）
            //  farm_import_min_gap_sec    两次导入最小间隔秒数 —— 规避同 IP 连续注册触发上游风控
            //  farm_max_accounts          号池容量上限 —— 防无限膨胀
            //  farm_reject_bot_flag       导入时拒绝 access_token 带 bot_flag_source=1 的号
            //  farm_disable_on_bot_flag   体检发现被标记的号自动停用（出池）
            //  farm_last_import_ts / farm_imported_total   节流与统计用的运行状态
            "farm_import_min_gap_sec" to "300", "farm_max_accounts" to "60",
            //  风控分级与判定策略（参照开源项目实践）
            //   risk_bot_flag_action  bot_flag 命中时的动作：warn 仅标记(默认) / disable 停用 / ignore 忽略
            //                        —— lij 已证 grok.com 的 botFlagSource 不可靠，chenyme 也只拿它选路由；
            //                        故默认不再"一票弃号"，仅标记 + 降低调度优先级
            //   cooldown_network_sec 基础设施抖动（超时/5xx/连接失败）的短冷却
            //   cooldown_risk_sec    上游风控类（限流/封锁/凭据被拒）的长冷却
            //   quality_probe_*      降智探测：真实短对话流式请求，看有没有 reasoning token
            "risk_bot_flag_action" to "warn", "cooldown_network_sec" to "90",
            "cooldown_risk_sec" to "1800", "quality_probe_enabled" to "0",
            "quality_probe_model" to "grok-4.6",
            "farm_reject_bot_flag" to "1", "farm_disable_on_bot_flag" to "1",
            "farm_last_import_ts" to "0", "farm_imported_total" to "0")
        /**
         * 记录内容字段的入库上限。取值理由：
         * - 输入 4000 字符 ≈ 一整轮较长对话；截断只影响超长多轮历史，日常排查完全够用。
         * - 输出/思考各 8000 字符：足够覆盖单次完整回复与推理链。
         * 三条合计上限约 20KB/条，即使 1 万条记录也仅约 200MB 的极端上限——
         * 而实际平均远小于此，配合默认 30 天保留期可把库稳定控制在合理范围。
         */
        private const val INPUT_LIMIT = 4000
        private const val OUTPUT_LIMIT = 8000
        private const val REASONING_LIMIT = 8000
        @Volatile private var instance: NativeStore? = null
        fun get(context: Context): NativeStore = instance ?: synchronized(this) { instance ?: NativeStore(context).also { instance = it } }
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
        private fun localMidnight(): Double {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            return (format.parse(format.format(Date()))?.time ?: System.currentTimeMillis()) / 1000.0
        }
    }
}
