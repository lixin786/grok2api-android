package com.grok2api.gateway

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 产号设置页：Material 卡片风（纯代码实现，对齐 taixu activity_settings 观感）。
 * 配置五邮源访问凭证 + 批次/补号参数，存 NativeStore.settings（saveSettings 白名单）。
 * 本项目是纯代码 View 体系（无 XML/material 依赖），用 GradientDrawable 圆角卡片 + 描边还原观感。
 */
class FarmSettingsActivity : Activity() {

    private val providers = listOf("mailtm", "duckmail", "yyds", "cloudflare", "cloudmail")
    private val providerLabels = listOf(
        "mail.tm（免配置）", "DuckMail（duckmail.sbs）", "YYDS（maliapi.215.im）",
        "Cloudflare（自建）", "Cloud Mail（自建）")

    private var spProvider: Spinner? = null
    private var etDuckKey: EditText? = null
    private var etYydsJwt: EditText? = null
    private var etYydsKey: EditText? = null
    private var etCfBase: EditText? = null
    private var etCfKey: EditText? = null
    private var etCfDomains: EditText? = null
    private var etCmBase: EditText? = null
    private var etCmToken: EditText? = null
    private var etCmDomains: EditText? = null
    private var etThreshold: EditText? = null
    private var etGap: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    // ---- 工具 ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun c(hex: String) = Color.parseColor(hex)
    private fun rounded(fill: Int, radiusDp: Int, stroke: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(strokeDp), stroke)
        }

    // ---- 主布局 ----
    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(c("#0F172A")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "设置"; setTextColor(c("#F1F5F9")); textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "配置邮箱服务与定时补号"; setTextColor(c("#64748B")); textSize = 13f; setPadding(0, dp(4), 0, dp(20))
        })

        col.addView(card("邮箱服务商", "选择收件服务并填写对应访问凭证") {
            label("当前服务商")
            spProvider = Spinner(this@FarmSettingsActivity).apply {
                adapter = ArrayAdapter(this@FarmSettingsActivity, android.R.layout.simple_spinner_dropdown_item, providerLabels)
                setBackgroundColor(c("#0F172A"))
                minimumHeight = dp(48)
            }
            addView(spProvider, fieldLp())
            field("DuckMail API Key", "（可选）Bearer 鉴权") { etDuckKey = it }
            field("YYDS JWT", "maliapi.215.im，与 API Key 二选一") { etYydsJwt = it }
            field("YYDS API Key", "X-API-Key 鉴权") { etYydsKey = it }
        })

        col.addView(card("Cloudflare（自建）", "自建 Cloudflare 邮箱服务") {
            field("API Base", "如 https://your-host") { etCfBase = it }
            field("API Key", "x-admin-auth 头") { etCfKey = it }
            field("收件域名", "逗号分隔，轮换使用") { etCfDomains = it }
        })

        col.addView(card("Cloud Mail（自建）", "自建 Cloud Mail 服务") {
            field("API Base", "如 https://your-host") { etCmBase = it }
            field("Public Token", "Authorization 头") { etCmToken = it }
            field("收件域名", "逗号分隔") { etCmDomains = it }
        })

        col.addView(card("批次与补号", "号间隔、可用率阈值") {
            field("号间隔（秒）", "默认 300") { etGap = it }
            field("补号阈值 %", "可用率低于此值提醒，默认 70") { etThreshold = it }
        })

        val save = Button(this).apply {
            text = "保存"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { onSave() }
        }
        col.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(20) })

        load()
        return scroll
    }

    // ---- 卡片内容构建器（LinearLayout 扩展，receiver = 卡片 body 容器）----

    private fun card(title: String, subtitle: String, body: LinearLayout.() -> Unit): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(c("#1E293B"), 16, c("#334155"), 1)
        }
        outer.addView(TextView(this).apply {
            text = title; setTextColor(c("#F1F5F9")); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        outer.addView(TextView(this).apply {
            text = subtitle; setTextColor(c("#64748B")); textSize = 12f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        val bodyHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        outer.addView(bodyHost)
        bodyHost.body()
        return outer
    }

    private fun LinearLayout.label(text: String) {
        addView(TextView(context).apply {
            this.text = text; setTextColor(c("#94A3B8")); textSize = 12f; setPadding(0, 0, 0, dp(4))
        })
    }

    private fun LinearLayout.field(labelText: String, hint: String, assign: (EditText) -> Unit) {
        label(labelText)
        val edit = EditText(context).apply {
            this.hint = hint
            setTextColor(c("#F1F5F9")); setHintTextColor(c("#475569"))
            textSize = 14f; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(c("#0F172A"), 8, c("#334155"), 1)
            isSingleLine = true
        }
        addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(10) })
        assign(edit)
    }

    private fun fieldLp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) }

    // ---- 加载 & 保存 ----

    private fun load() {
        val settings = NativeCore.store(this).getSettings()
        val provider = settings.optString("farm_provider", "mailtm")
        spProvider?.setSelection(providers.indexOf(provider).coerceAtLeast(0))
        etDuckKey?.setText(settings.optString("duckmail_api_key"))
        etYydsJwt?.setText(settings.optString("yyds_jwt"))
        etYydsKey?.setText(settings.optString("yyds_api_key"))
        etCfBase?.setText(settings.optString("cloudflare_api_base"))
        etCfKey?.setText(settings.optString("cloudflare_api_key"))
        etCfDomains?.setText(settings.optString("cloudflare_domains"))
        etCmBase?.setText(settings.optString("cloudmail_api_base"))
        etCmToken?.setText(settings.optString("cloudmail_public_token"))
        etCmDomains?.setText(settings.optString("cloudmail_domains"))
        etThreshold?.setText(settings.optString("farm_auto_threshold", "70"))
        etGap?.setText(settings.optString("farm_import_min_gap_sec", "300"))
    }

    private fun onSave() {
        val provider = providers.getOrNull(spProvider?.selectedItemPosition ?: 0) ?: "mailtm"
        val json = JSONObject()
            .put("farm_provider", provider)
            .put("duckmail_api_key", etDuckKey?.text?.toString()?.trim().orEmpty())
            .put("yyds_jwt", etYydsJwt?.text?.toString()?.trim().orEmpty())
            .put("yyds_api_key", etYydsKey?.text?.toString()?.trim().orEmpty())
            .put("cloudflare_api_base", etCfBase?.text?.toString()?.trim().orEmpty())
            .put("cloudflare_api_key", etCfKey?.text?.toString()?.trim().orEmpty())
            .put("cloudflare_domains", etCfDomains?.text?.toString()?.trim().orEmpty())
            .put("cloudmail_api_base", etCmBase?.text?.toString()?.trim().orEmpty())
            .put("cloudmail_public_token", etCmToken?.text?.toString()?.trim().orEmpty())
            .put("cloudmail_domains", etCmDomains?.text?.toString()?.trim().orEmpty())
            .put("farm_auto_threshold", etThreshold?.text?.toString()?.trim().orEmpty().ifBlank { "70" })
            .put("farm_import_min_gap_sec", etGap?.text?.toString()?.trim().orEmpty().ifBlank { "300" })
        NativeCore.store(this).saveSettings(json)
        Toast.makeText(this, "已保存（邮源：$provider）", Toast.LENGTH_SHORT).show()
        finish()
    }
}