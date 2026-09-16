package com.grok2api.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * xAI Grok `/responses` 线格式与 OpenAI Chat 格式之间的双向互转。
 *
 * 设计取舍：网关内部**统一用 OpenAI Chat 格式作为规范中间格式**（与改造前一致），
 * 只在最外两层做转换——出口把 Chat 请求翻成上游要的 Responses 请求，入口把上游的
 * Responses SSE 事件翻回 Chat chunk。这样 UI、SQLite 存储、用量统计、以及
 * `/v1/messages`、`/v1/responses` 两个入站协议转换器全部原样复用，改动面最小。
 *
 * 上游协议要点（来自 grok2api 逆向，见 grok2api-protocol-ref）：
 *  - 端点 `POST https://cli-chat-proxy.grok.com/v1/responses`
 *  - `tools` 是**扁平** Responses 形态（`{type,name,parameters}`），不是 Chat 的嵌套形态
 *  - `tools[].parameters` 根 schema 必须是**非 nullable 的 object**，否则上游 400
 *  - assistant 历史的文本 part 是 `output_text`，user/system 是 `input_text`
 *  - `function_call` / `function_call_output` 只带 `{type,call_id,name,arguments}` / `{type,call_id,output}`
 *  - 流式终止靠 `response.completed`；`[DONE]` 不能作为唯一判据
 */
object XaiProtocol {
    const val CLIENT_VERSION = "1.0.4"
    const val CLIENT_IDENTIFIER = "grok-shell"
    const val TOKEN_AUTH = "xai-grok-cli"
    const val USER_AGENT = "grok-shell/1.0.4 (linux; x86_64)"

    const val OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val OAUTH_SCOPE = "openid profile email offline_access grok-cli:access api:access " +
        "conversations:read conversations:write workspaces:read workspaces:write"
    const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
    const val TOKEN_URL = "https://auth.x.ai/oauth2/token"
    const val DEVICE_VERIFY_URL = "https://accounts.x.ai/oauth2/device"

    /** 默认模型：客户端没指定 model 时用它。 */
    const val DEFAULT_MODEL = "grok-4.6"

    /** 客户端可能沿用的旧别名 → 真实上游模型。 */
    private val MODEL_ALIASES = mapOf(
        "auto" to DEFAULT_MODEL,
        "grok" to DEFAULT_MODEL,
        "grok-latest" to DEFAULT_MODEL,
        "default-model" to DEFAULT_MODEL,
        "grok-4" to "grok-4.5",
        "grok-beta" to DEFAULT_MODEL
    )

    // ---------------------------------------------------------------- 模型目录

    /**
     * 模型能力表。`context` 单位 token；`efforts` 为空表示该模型不接受 `reasoning.effort`
     * （composer / build-0.1 传了会被上游拒绝，必须剥掉）。
     */
    data class ModelSpec(
        val id: String,
        val context: Long,
        val vision: Boolean,
        val efforts: List<String>
    ) {
        val reasoning: Boolean get() = efforts.isNotEmpty() && efforts != listOf("none")
    }

    private val UNKNOWN = ModelSpec("", 128_000L, false, emptyList())

    private val SPECS: Map<String, ModelSpec> = listOf(
        ModelSpec("grok-4.6", 500_000L, true, listOf("low", "medium", "high", "xhigh")),
        ModelSpec("grok-4.5", 500_000L, true, listOf("low", "medium", "high")),
        ModelSpec("grok-4.3", 1_000_000L, true, listOf("none", "low", "medium", "high")),
        ModelSpec("grok-build-0.1", 256_000L, false, emptyList()),
        ModelSpec("grok-4.20-0309-reasoning", 2_000_000L, true, listOf("low", "medium", "high")),
        ModelSpec("grok-4.20-0309-non-reasoning", 2_000_000L, true, listOf("none")),
        ModelSpec("grok-4.20-multi-agent-0309", 2_000_000L, true, listOf("low", "medium", "high", "xhigh")),
        ModelSpec("grok-3-mini", 131_072L, false, listOf("low", "medium", "high")),
        ModelSpec("grok-3-mini-fast", 131_072L, false, listOf("low", "medium", "high")),
        ModelSpec("grok-composer-2.5-fast", 200_000L, false, emptyList())
    ).associateBy { it.id }

    fun specOf(model: String): ModelSpec = SPECS[model] ?: UNKNOWN.copy(id = model)

    /**
     * 拆出「模型 + effort 后缀」。上游网关把每个多级模型展开成 `grok-4.6-high` 这类别名，
     * 客户端选了别名时要把后缀还原成 `reasoning.effort`。
     */
    fun splitEffort(model: String): Pair<String, String?> {
        val dash = model.lastIndexOf('-')
        if (dash <= 0) return model to null
        val suffix = model.substring(dash + 1).lowercase()
        if (suffix != "low" && suffix != "medium" && suffix != "high" && suffix != "xhigh") return model to null
        val base = model.substring(0, dash)
        val spec = SPECS[base] ?: return model to null
        return if (suffix in spec.efforts) base to suffix else model to null
    }

    /** 把一个上游模型名转成对外暴露的 OpenAI 风格模型条目。 */
    fun modelEntry(id: String, catalogName: String? = null, exposeAliases: Boolean = true): JSONObject {
        val spec = specOf(id)
        val entry = JSONObject()
            .put("id", id).put("object", "model").put("created", 1_700_000_000)
            .put("owned_by", "xai").put("name", catalogName?.takeIf { it.isNotBlank() } ?: id)
            .put("context_length", spec.context).put("max_output_tokens", 0)
            .put("supports_images", spec.vision).put("supports_image", spec.vision)
            .put("supports_tools", true)
            .put("vision", spec.vision).put("image", spec.vision)
            .put("modality", if (spec.vision) "multimodal" else "text")
            .put("modalities", JSONArray().put("text").apply { if (spec.vision) put("image") })
            .put("input_modalities", JSONArray().put("text").apply { if (spec.vision) put("image") })
            .put("output_modalities", JSONArray().put("text"))
            .put("reasoning", JSONObject()
                .put("supportsReasoning", spec.reasoning)
                .put("onlyReasoning", false)
                .put("efforts", JSONArray().apply { spec.efforts.forEach { put(it) } }))
        if (exposeAliases && spec.efforts.size > 1) {
            // 展开 effort 别名，客户端可像选模型一样直接选推理档位。
            entry.put("effort_aliases", JSONArray().apply {
                spec.efforts.filter { it != "none" }.forEach { put("$id-$it") }
            })
        }
        return entry
    }

    /** 展开 effort 别名条目（与上游网关行为一致，便于客户端直接选择）。 */
    fun withEffortAliases(models: List<JSONObject>): JSONArray {
        val out = JSONArray()
        models.forEach { out.put(it) }
        models.forEach { model ->
            val id = model.optString("id")
            val spec = SPECS[id] ?: return@forEach
            if (spec.efforts.size <= 1) return@forEach
            spec.efforts.filter { it != "none" }.forEach { effort ->
                val alias = JSONObject(model.toString())
                alias.put("id", "$id-$effort")
                alias.put("name", "${model.optString("name", id)} · ${effortLabel(effort)}")
                alias.put("effort", effort)
                alias.put("base_model", id)
                alias.remove("effort_aliases")
                out.put(alias)
            }
        }
        return out
    }

    private fun effortLabel(effort: String) = when (effort) {
        "low" -> "快速"
        "medium" -> "标准"
        "high" -> "深入"
        "xhigh" -> "极致"
        else -> effort
    }

    // ------------------------------------------------- Chat 请求 → Responses 请求

    /**
     * 把网关内部的 OpenAI Chat 请求体翻成 xAI `/responses` 请求体。
     *
     * 关键约束（漏掉会 400 或静默降质）：
     *  - `store` 必须显式 false，`include` 必须带 `reasoning.encrypted_content`
     *  - `tools` 必须拍平成 Responses 形态，且 `parameters` 根必须是非 nullable object
     *  - assistant 的正文用字符串（绕开 OutputMessage 对 id/status 的输入限制）
     *  - `presence_penalty` / `frequency_penalty` / `seed` / `stop` 上游不支持，直接丢弃
     */
    fun chatToResponses(chat: JSONObject): JSONObject {
        val input = JSONArray()
        val messages = chat.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            appendMessage(message, input)
        }

        val requested = chat.optString("model").ifBlank { DEFAULT_MODEL }
        val (baseModel, aliasEffort) = splitEffort(requested)
        val model = MODEL_ALIASES[baseModel.lowercase()] ?: baseModel

        val out = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("stream", true)
            .put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content"))

        copy(chat, out, "temperature", "top_p", "parallel_tool_calls", "metadata")
        // max_tokens / max_completion_tokens 都归一到 max_output_tokens
        val maxOut = when {
            chat.has("max_output_tokens") -> chat.optLong("max_output_tokens")
            chat.has("max_completion_tokens") -> chat.optLong("max_completion_tokens")
            else -> chat.optLong("max_tokens")
        }
        if (maxOut > 0) out.put("max_output_tokens", maxOut)

        chat.optJSONObject("response_format")?.let { format ->
            runCatching { out.put("text", JSONObject().put("format", convertResponseFormat(format))) }
        }

        // 推理档位：别名后缀优先，其次是客户端显式 reasoning_effort
        val spec = specOf(model)
        val explicit = chat.optString("reasoning_effort").takeIf { it.isNotBlank() }
        val effort = when {
            aliasEffort != null -> aliasEffort
            explicit != null -> normalizeEffort(explicit, spec)
            else -> null
        }
        if (spec.efforts.isNotEmpty()) {
            val reasoning = JSONObject()
            if (effort != null && effort in spec.efforts) reasoning.put("effort", effort)
            // 摘要档位缺失时上游默认静默，显式要 concise 才能稳定拿到思考链
            reasoning.put("summary", if (spec.efforts.contains("none") && spec.efforts.size == 1) "auto" else "concise")
            if (reasoning.length() > 0) out.put("reasoning", reasoning)
        }

        chat.optJSONArray("tools")?.let { tools ->
            val converted = convertTools(tools)
            if (converted.length() > 0) {
                out.put("tools", converted)
                chat.opt("tool_choice")?.let { choice -> convertToolChoice(choice)?.let { out.put("tool_choice", it) } }
            }
        }
        return out
    }

    /** `minimal`/`max` 是客户端常用别名；`max` 只有 grok-4.6 支持，其余降到 high。 */
    private fun normalizeEffort(raw: String, spec: ModelSpec): String = when (raw.lowercase()) {
        "minimal", "none" -> if (spec.efforts.contains("none")) "none" else "low"
        "low", "medium", "high" -> raw.lowercase()
        "xhigh" -> if (spec.efforts.contains("xhigh")) "xhigh" else "high"
        "max" -> if (spec.efforts.contains("xhigh")) "xhigh" else "high"
        else -> raw.lowercase()
    }

    private fun convertResponseFormat(format: JSONObject): JSONObject {
        val type = format.optString("type")
        return when (type) {
            "json_schema" -> {
                val schema = format.optJSONObject("json_schema") ?: JSONObject()
                JSONObject().put("type", "json_schema")
                    .put("name", schema.optString("name", "response"))
                    .put("schema", schema.opt("schema") ?: JSONObject())
                    .apply { if (schema.has("strict")) put("strict", schema.optBoolean("strict")) }
            }
            "json_object" -> JSONObject().put("type", "json_object")
            else -> format
        }
    }

    /** Chat 的嵌套工具声明 → Responses 的扁平形态。 */
    private fun convertTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val fn = tool.optJSONObject("function")
            if (fn == null) {
                // 已经是 Responses 原生形态（web_search / x_search 等），原样透传
                if (tool.optString("type").isNotBlank()) out.put(JSONObject(tool.toString()))
                continue
            }
            val name = fn.optString("name")
            if (name.isBlank()) continue
            val flat = JSONObject().put("type", "function").put("name", name)
            fn.optString("description").takeIf { it.isNotBlank() }?.let { flat.put("description", it) }
            flat.put("parameters", normalizeParametersRoot(fn.opt("parameters")))
            if (fn.has("strict")) flat.put("strict", fn.optBoolean("strict"))
            out.put(flat)
        }
        return out
    }

    private fun convertToolChoice(choice: Any?): Any? = when (choice) {
        is String -> choice
        is JSONObject -> {
            val nested = choice.optJSONObject("function")
            if (nested != null) JSONObject().put("type", "function").put("name", nested.optString("name"))
            else JSONObject(choice.toString())
        }
        else -> null
    }

    /**
     * 把 `parameters` 的根 schema 归一成「非 nullable 的 object」。
     *
     * 上游对这个位置校验很严：`{"type":["object","null"]}`、`anyOf:[{object},{null}]`
     * 都会被拒。MCP 风格的工具声明经常带 null 分支，这里必须剥掉。
     */
    internal fun normalizeParametersRoot(raw: Any?): JSONObject {
        val schema = raw as? JSONObject ?: return JSONObject().put("type", "object")
        for (key in arrayOf("anyOf", "oneOf")) {
            val branches = schema.optJSONArray(key) ?: continue
            for (i in 0 until branches.length()) {
                val branch = branches.optJSONObject(i) ?: continue
                if (looksLikeObject(branch)) return sanitizeObjectSchema(branch)
            }
        }
        return sanitizeObjectSchema(schema)
    }

    private fun looksLikeObject(schema: JSONObject): Boolean {
        if (schema.optString("type") == "object") return true
        schema.optJSONArray("type")?.let { types ->
            for (i in 0 until types.length()) if (types.optString(i) == "object") return true
        }
        return schema.has("properties") || schema.has("required") || schema.has("additionalProperties")
    }

    private fun sanitizeObjectSchema(schema: JSONObject): JSONObject {
        val out = JSONObject(schema.toString())
        when (val type = out.opt("type")) {
            is JSONArray -> {
                val kept = JSONArray()
                for (i in 0 until type.length()) {
                    val value = type.optString(i)
                    if (value.isNotBlank() && value != "null") kept.put(value)
                }
                if (kept.length() == 0 || kept.toString().contains("object")) out.put("type", "object")
                else out.put("type", kept.optString(0))
            }
            is String -> if (type == "null" || type.isBlank()) out.put("type", "object")
            else -> out.put("type", "object")
        }
        // properties 里每个字段同样要清掉 null 分支，否则下游解析工具 schema 会失败
        out.optJSONObject("properties")?.let { props ->
            val cleaned = JSONObject()
            props.keys().forEach { key ->
                val value = props.optJSONObject(key)
                cleaned.put(key, if (value == null) props.opt(key) else sanitizeObjectSchema(value))
            }
            out.put("properties", cleaned)
        }
        return out
    }

    private fun appendMessage(message: JSONObject, input: JSONArray) {
        val role = message.optString("role", "user").let { if (it == "model") "assistant" else it }
        val content = message.opt("content")
        when (role) {
            "tool" -> {
                val callId = message.optString("tool_call_id")
                if (callId.isBlank()) return
                input.put(JSONObject().put("type", "function_call_output")
                    .put("call_id", callId).put("output", toolOutput(content)))
                return
            }
            "assistant" -> {
                val text = plainText(content, output = true)
                if (text.isNotEmpty()) {
                    // 官方 CLI 把 assistant 正文合并成字符串：既保语义，又绕开 OutputMessage
                    // 对 id/status 的输入限制。
                    input.put(JSONObject().put("type", "message").put("role", "assistant").put("content", text))
                }
                message.optJSONArray("tool_calls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val fn = call.optJSONObject("function") ?: continue
                        val callId = call.optString("id").ifBlank { randomId("call_") }
                        input.put(JSONObject().put("type", "function_call").put("call_id", callId)
                            .put("name", fn.optString("name"))
                            .put("arguments", fn.optString("arguments").ifBlank { "{}" }))
                    }
                }
                return
            }
            else -> {
                // system / developer / user
                val target = when (role) {
                    "system", "developer" -> role
                    "user" -> "user"
                    else -> "user"
                }
                val parts = contentParts(content, output = false)
                if (parts != null) {
                    input.put(JSONObject().put("type", "message").put("role", target).put("content", parts))
                } else {
                    val text = plainText(content, output = false)
                    if (text.isNotEmpty()) {
                        input.put(JSONObject().put("type", "message").put("role", target).put("content", text))
                    }
                }
            }
        }
    }

    /** 纯文本时返回合并字符串；含图片时返回 null，交由 [contentParts] 走数组路径。 */
    private fun plainText(content: Any?, output: Boolean): String {
        if (content is String) return content
        val parts = content as? JSONArray ?: return content?.toString().orEmpty()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text", "input_text" -> text.append(part.optString("text"))
                "output_text", "summary_text" -> text.append(part.optString("text"))
                "refusal" -> text.append(part.optString("refusal", part.optString("text")))
            }
        }
        return text.toString()
    }

    /** 含图片/文件时构造 Responses 内容数组；纯文本返回 null。 */
    private fun contentParts(content: Any?, output: Boolean): JSONArray? {
        val parts = content as? JSONArray ?: return null
        var multimodal = false
        for (i in 0 until parts.length()) {
            val type = parts.optJSONObject(i)?.optString("type").orEmpty()
            if (type == "image_url" || type == "input_image") { multimodal = true; break }
        }
        if (!multimodal) return null
        val out = JSONArray()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text", "input_text" -> out.put(JSONObject().put("type", "input_text").put("text", part.optString("text")))
                "output_text" -> out.put(JSONObject().put("type", "output_text").put("text", part.optString("text")))
                "image_url" -> {
                    val raw = part.opt("image_url")
                    val url = when (raw) {
                        is String -> raw
                        is JSONObject -> raw.optString("url")
                        else -> ""
                    }
                    if (url.isNotEmpty()) {
                        val image = JSONObject().put("type", "input_image").put("image_url", url)
                        val detail = (raw as? JSONObject)?.optString("detail").orEmpty()
                        image.put("detail", detail.ifBlank { "auto" })
                        out.put(image)
                    }
                }
            }
        }
        return if (out.length() == 0) null else out
    }

    private fun toolOutput(content: Any?): Any = when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> content.toString()
        else -> content.toString()
    }

    private fun copy(from: JSONObject, to: JSONObject, vararg keys: String) {
        for (key in keys) if (from.has(key) && !from.isNull(key)) to.put(key, from.get(key))
    }

    internal fun randomId(prefix: String): String =
        prefix + UUID.randomUUID().toString().replace("-", "").take(24)
}

/**
 * 上游 Responses SSE → OpenAI Chat chunk 的有状态翻译器。
 *
 * 输出的是标准 Chat chunk JSON 字符串，直接喂给现有的 [ChatStreamCollector] 与
 * [AnthropicStreamConverter] / [ResponsesStreamConverter]，下游转换器无需任何改动。
 *
 * 终止语义：只有 `response.completed` 才算正常结束；`response.failed` /
 * `response.incomplete` / `error` 都通过 [failure] 暴露，由调用方转成流内错误。
 */
class XaiStreamTranslator(private var model: String) {
    private val responseId = XaiProtocol.randomId("chatcmpl_")
    private val createdAt = System.currentTimeMillis() / 1000

    /** output_index → Chat tool_calls 数组下标（Chat 要求工具下标从 0 连续）。 */
    private val toolIndexByOutput = linkedMapOf<Int, Int>()
    /** call_id / item_id → Chat 工具下标，用于 delta 缺 output_index 时反查。 */
    private val toolIndexByKey = linkedMapOf<String, Int>()
    private val streamedArgs = linkedMapOf<Int, StringBuilder>()

    private var emittedRole = false
    private var nextToolIndex = 0
    private var sawToolCall = false
    private var sawText = false
    private var finishReason: String? = null
    private var usage: JSONObject? = null
    private var reasoningSource: String? = null
    private var completed = false

    /** 上游报错时的文案；非空表示本次流应视为失败。 */
    var failure: String? = null
        private set

    fun feed(data: String): List<String> {
        if (data == "[DONE]") return emptyList()
        val event = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        val type = event.optString("type")
        if (type.isBlank()) return emptyList()
        event.optString("model").takeIf { it.isNotBlank() }?.let { eventModel ->
            if (!eventModel.startsWith("resp_")) model = eventModel
        }
        val out = ArrayList<String>(4)
        when (type) {
            "response.created", "response.in_progress" -> {
                event.optJSONObject("response")?.let { response ->
                    response.optString("model").takeIf { it.isNotBlank() }?.let { model = it }
                    mergeUsage(response.optJSONObject("usage"))
                }
                ensureRole(out)
            }
            "response.output_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    ensureRole(out)
                    sawText = true
                    out.add(chunk(JSONObject().put("content", delta)).toString())
                }
            }
            "response.reasoning_summary_text.delta" -> emitReasoning(out, "summary", event.optString("delta"))
            "response.reasoning_text.delta" -> emitReasoning(out, "text", event.optString("delta"))
            "response.refusal.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    ensureRole(out)
                    sawText = true
                    out.add(chunk(JSONObject().put("refusal", delta)).toString())
                }
            }
            "response.output_item.added" -> {
                val item = event.optJSONObject("item") ?: return out
                if (item.optString("type") == "function_call") registerTool(event, item, out)
            }
            "response.function_call_arguments.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    val index = resolveToolIndex(event) ?: return out
                    streamedArgs.getOrPut(index) { StringBuilder() }.append(delta)
                    out.add(toolChunk(index, JSONObject().put("arguments", delta)))
                }
            }
            "response.function_call_arguments.done" -> Unit // 全量参数在 output_item.done 里统一收口
            "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return out
                if (item.optString("type") == "function_call") completeTool(event, item, out)
            }
            "response.completed" -> {
                val response = event.optJSONObject("response")
                response?.optString("model")?.takeIf { it.isNotBlank() }?.let { model = it }
                mergeUsage(response?.optJSONObject("usage"))
                completed = true
                finishReason = if (sawToolCall) "tool_calls" else "stop"
            }
            "response.incomplete" -> {
                event.optJSONObject("response")?.let { mergeUsage(it.optJSONObject("usage")) }
                completed = true
                finishReason = "length"
            }
            "response.failed" -> {
                failure = errorText(event) ?: "上游返回 response.failed"
                completed = true
                finishReason = "stop"
            }
            "error", "response.error" -> {
                failure = errorText(event) ?: "上游返回错误事件"
                completed = true
                finishReason = "stop"
            }
            else -> Unit // response.doom_loop_check / xai.internal.* 等私有事件一律丢弃
        }
        return out
    }

    /** 流收尾：补 finish_reason + usage + `[DONE]`。未收到终止事件时视为失败。 */
    fun finish(): List<String> {
        val out = ArrayList<String>(3)
        if (failure == null && !completed) failure = "上游流意外中断（未收到 response.completed）"
        if (!emittedRole) ensureRole(out)
        val delta = JSONObject()
        val tail = chunk(delta)
        tail.getJSONArray("choices").getJSONObject(0)
            .put("finish_reason", finishReason ?: "stop")
        usage?.let { tail.put("usage", it) }
        out.add(tail.toString())
        out.add("[DONE]")
        return out
    }

    private fun emitReasoning(out: ArrayList<String>, source: String, delta: String) {
        if (delta.isEmpty()) return
        // 上游可能同时推 summary 与 raw 两条思考链，只取先到的那条，避免客户端里出现重复思考
        val current = reasoningSource
        if (current != null && current != source) return
        reasoningSource = source
        ensureRole(out)
        out.add(chunk(JSONObject().put("reasoning_content", delta).put("reasoning", delta)).toString())
    }

    private fun ensureRole(out: ArrayList<String>) {
        if (emittedRole) return
        emittedRole = true
        out.add(chunk(JSONObject().put("role", "assistant").put("content", "")).toString())
    }

    private fun registerTool(event: JSONObject, item: JSONObject, out: ArrayList<String>) {
        val key = item.optString("id").ifBlank { item.optString("call_id") }
        val index = nextToolIndex++
        sawToolCall = true
        val outputIndex = event.optInt("output_index", -1)
        if (outputIndex >= 0) toolIndexByOutput[outputIndex] = index
        if (key.isNotBlank()) toolIndexByKey[key] = index
        item.optString("call_id").takeIf { it.isNotBlank() }?.let { toolIndexByKey[it] = index }
        ensureRole(out)
        out.add(toolChunk(index, JSONObject().put("id", item.optString("call_id").ifBlank { key })
            .put("type", "function")
            .put("function", JSONObject().put("name", item.optString("name")).put("arguments", ""))))
        streamedArgs.getOrPut(index) { StringBuilder() }
    }

    /** output_item.done 是工具参数的权威来源；若增量阶段没流完整，这里补发差额。 */
    private fun completeTool(event: JSONObject, item: JSONObject, out: ArrayList<String>) {
        val index = resolveToolIndex(event)
            ?: toolIndexByKey[item.optString("call_id")]
            ?: toolIndexByKey[item.optString("id")]
            ?: return
        val authoritative = item.optString("arguments")
        val streamed = streamedArgs.getOrPut(index) { StringBuilder() }.toString()
        if (authoritative.isNotEmpty() && authoritative != streamed) {
            val remainder = if (authoritative.startsWith(streamed)) authoritative.substring(streamed.length) else authoritative
            if (remainder.isNotEmpty()) out.add(toolChunk(index, JSONObject().put("arguments", remainder)))
            streamedArgs[index] = StringBuilder(authoritative)
        }
    }

    private fun resolveToolIndex(event: JSONObject): Int? {
        event.optString("item_id").takeIf { it.isNotBlank() }?.let { id ->
            toolIndexByKey[id]?.let { return it }
        }
        event.optString("call_id").takeIf { it.isNotBlank() }?.let { id ->
            toolIndexByKey[id]?.let { return it }
        }
        if (event.has("output_index")) toolIndexByOutput[event.optInt("output_index")]?.let { return it }
        return null
    }

    private fun toolChunk(index: Int, functionOrCall: JSONObject): String {
        val call = JSONObject().put("index", index)
        if (functionOrCall.has("id")) call.put("id", functionOrCall.opt("id"))
        if (functionOrCall.has("type")) call.put("type", functionOrCall.opt("type"))
        when {
            functionOrCall.has("function") -> call.put("function", functionOrCall.getJSONObject("function"))
            else -> call.put("function", JSONObject().put("arguments", functionOrCall.optString("arguments")))
        }
        return chunk(JSONObject().put("tool_calls", JSONArray().put(call))).toString()
    }

    private fun chunk(delta: JSONObject): JSONObject = JSONObject()
        .put("id", responseId).put("object", "chat.completion.chunk").put("created", createdAt)
        .put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", JSONObject.NULL)))

    /**
     * xAI 的 usage 分散在 created / in_progress / completed 三个事件里，每个只带一部分字段，
     * 所以必须做「非零才覆盖」的合并，不能用最后一个事件整体替换。
     */
    private fun mergeUsage(source: JSONObject?) {
        if (source == null) return
        val target = usage ?: JSONObject().also { usage = it }
        val prompt = source.optLong("input_tokens", source.optLong("prompt_tokens", 0L))
        val completion = source.optLong("output_tokens", source.optLong("completion_tokens", 0L))
        val total = source.optLong("total_tokens", 0L)
        if (prompt > 0) target.put("prompt_tokens", prompt)
        if (completion > 0) target.put("completion_tokens", completion)
        val resolvedPrompt = target.optLong("prompt_tokens", 0L)
        val resolvedCompletion = target.optLong("completion_tokens", 0L)
        if (total > 0) target.put("total_tokens", total)
        else if (resolvedPrompt + resolvedCompletion > 0) target.put("total_tokens", resolvedPrompt + resolvedCompletion)
        val reasoning = source.optJSONObject("output_tokens_details")?.optLong("reasoning_tokens", 0L) ?: 0L
        if (reasoning > 0) {
            target.put("completion_tokens_details", JSONObject().put("reasoning_tokens", reasoning))
        }
        val cached = source.optJSONObject("input_tokens_details")?.optLong("cached_tokens", 0L) ?: 0L
        if (cached > 0) target.put("prompt_tokens_details", JSONObject().put("cached_tokens", cached))
        // xAI 特有的成本字段，用于用量记录页展示
        val ticks = source.optLong("cost_in_usd_ticks", 0L)
        if (ticks > 0) target.put("cost_in_usd_ticks", ticks)
    }

    private fun errorText(event: JSONObject): String? {
        event.optJSONObject("response")?.opt("error")?.let { return renderError(it) }
        event.opt("error")?.let { if (it != JSONObject.NULL) return renderError(it) }
        if (event.has("message")) return event.optString("message")
        if (event.has("detail")) return event.optString("detail")
        return null
    }

    private fun renderError(value: Any?): String? = when (value) {
        is JSONObject -> {
            val message = value.optString("message").ifBlank { value.optString("error") }
            val code = value.optString("code")
            when {
                message.isNotBlank() && code.isNotBlank() -> "$message ($code)"
                message.isNotBlank() -> message
                code.isNotBlank() -> code
                else -> null
            }
        }
        is String -> value
        else -> null
    }
}
