package com.grok2api.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Contract-preserving adapters between Anthropic/Responses and OpenAI Chat. */
object ProtocolAdapters {
    fun anthropicToChat(body: JSONObject): JSONObject {
        val messages = JSONArray()
        val system = body.opt("system")
        val systemText = blockText(system)
        if (systemText.isNotEmpty()) messages.put(JSONObject().put("role", "system").put("content", systemText))

        val input = body.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until input.length()) {
            val message = input.optJSONObject(i) ?: continue
            val role = message.optString("role")
            val content = message.opt("content")
            if (content is String) {
                messages.put(JSONObject().put("role", role).put("content", content))
                continue
            }
            val blocks = content as? JSONArray ?: continue
            if (role == "user") {
                val text = StringBuilder()
                val images = JSONArray()
                val toolResults = JSONArray()
                for (j in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(j) ?: continue
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "image" -> {
                            val source = block.optJSONObject("source") ?: JSONObject()
                            val data = source.optString("data")
                            if (data.isNotEmpty()) images.put(JSONObject().put("type", "image_url").put(
                                "image_url", JSONObject().put("url", "data:${source.optString("media_type", "image/png")};base64,$data")
                            ))
                        }
                        "tool_result" -> {
                            val value = block.opt("content")
                            toolResults.put(JSONObject().put("role", "tool")
                                .put("tool_call_id", block.optString("tool_use_id"))
                                .put("content", if (value is JSONArray) blockText(value) else value?.toString().orEmpty()))
                        }
                    }
                }
                if (text.isNotEmpty() || images.length() > 0) {
                    val user = JSONObject().put("role", "user")
                    if (images.length() == 0) user.put("content", text.toString()) else {
                        val parts = JSONArray()
                        if (text.isNotEmpty()) parts.put(JSONObject().put("type", "text").put("text", text.toString()))
                        for (j in 0 until images.length()) parts.put(images.get(j))
                        user.put("content", parts)
                    }
                    messages.put(user)
                }
                for (j in 0 until toolResults.length()) messages.put(toolResults.get(j))
            } else if (role == "assistant") {
                val text = StringBuilder()
                val calls = JSONArray()
                for (j in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(j) ?: continue
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "tool_use" -> calls.put(JSONObject()
                            .put("id", block.optString("id", randomId("call_")))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", block.optString("name"))
                                .put("arguments", jsonString(block.opt("input") ?: JSONObject()))))
                    }
                }
                val out = JSONObject().put("role", "assistant").put("content", text.toString())
                if (calls.length() > 0) out.put("tool_calls", calls)
                messages.put(out)
            } else {
                val text = blockText(blocks)
                if (text.isNotEmpty()) messages.put(JSONObject().put("role", role).put("content", text))
            }
        }

        val out = JSONObject().put("messages", messages).put("stream", true)
        copy(body, out, "model", "max_tokens", "temperature", "top_p")
        if (body.has("stop_sequences")) out.put("stop", body.get("stop_sequences"))
        else if (body.has("stop")) out.put("stop", body.get("stop"))
        body.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                if (tool.has("function")) converted.put(tool) else {
                    val fn = JSONObject().put("name", tool.optString("name"))
                    copy(tool, fn, "description")
                    if (tool.has("input_schema")) fn.put("parameters", tool.get("input_schema"))
                    converted.put(JSONObject().put("type", "function").put("function", fn))
                }
            }
            out.put("tools", converted)
        }
        if (body.has("tool_choice")) {
            val choice = body.get("tool_choice")
            if (choice is JSONObject) {
                val type = choice.optString("type", "any")
                out.put("tool_choice", when (type) {
                    "auto", "none" -> type
                    "any" -> "required"
                    "tool" -> JSONObject().put("type", "function").put("function", JSONObject().put("name", choice.optString("name")))
                    else -> type
                })
            } else out.put("tool_choice", choice)
        }
        return out
    }

    fun responsesToChat(body: JSONObject): JSONObject {
        val messages = JSONArray()
        val instructions = body.optString("instructions")
        if (instructions.isNotEmpty()) messages.put(JSONObject().put("role", "system").put("content", instructions))
        when (val input = body.opt("input")) {
            is String -> messages.put(JSONObject().put("role", "user").put("content", input))
            is JSONArray -> convertResponseItems(input, messages)
        }
        val out = JSONObject().put("messages", messages).put("stream", true)
        copy(body, out, "model", "tool_choice", "temperature", "top_p", "stop", "seed",
            "presence_penalty", "frequency_penalty", "response_format", "reasoning_effort")
        if (body.has("max_output_tokens")) out.put("max_tokens", body.get("max_output_tokens"))
        else if (body.has("max_tokens")) out.put("max_tokens", body.get("max_tokens"))
        body.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                if (tool.optString("type") != "function") continue
                if (tool.has("function")) converted.put(tool) else {
                    val fn = JSONObject().put("name", tool.optString("name"))
                    copy(tool, fn, "description", "parameters", "strict")
                    converted.put(JSONObject().put("type", "function").put("function", fn))
                }
            }
            out.put("tools", converted)
        }
        return out
    }

    fun estimateAnthropicTokens(body: JSONObject): Int {
        var chars = blockText(body.opt("system")).length
        var count = 0
        val messages = body.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            count++
            chars += blockText(message.opt("content")).length
        }
        return chars / 4 + count * 4 + 4
    }

    /** Removes empty upstream deltas that create blank reasoning/text blocks in clients. */
    fun sanitizeChatData(data: String): String? {
        if (data == "[DONE]") return data
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return data
        val choices = chunk.optJSONArray("choices") ?: return data
        val kept = JSONArray()
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                for (key in arrayOf("content", "reasoning_content", "reasoning", "refusal", "function_call", "tool_calls")) {
                    if (delta.has(key) && isEmpty(delta.opt(key))) delta.remove(key)
                }
                if (delta.length() == 0) {
                    choice.remove("delta")
                    if (choice.optString("finish_reason").isEmpty()) continue
                }
            }
            kept.put(choice)
        }
        if (kept.length() == 0 && choices.length() > 0) return null
        chunk.put("choices", kept)
        return chunk.toString()
    }

    private fun convertResponseItems(items: JSONArray, messages: JSONArray) {
        var pendingText: String? = null
        var pendingCalls = JSONArray()
        fun flush() {
            if (pendingText != null || pendingCalls.length() > 0) {
                val msg = JSONObject().put("role", "assistant").put("content", pendingText.orEmpty())
                if (pendingCalls.length() > 0) msg.put("tool_calls", pendingCalls)
                messages.put(msg)
                pendingText = null
                pendingCalls = JSONArray()
            }
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val type = item.optString("type")
            val role = item.optString("role")
            when {
                type == "function_call" -> {
                    if (pendingText == null) pendingText = ""
                    pendingCalls.put(JSONObject().put("id", item.optString("call_id", item.optString("id", randomId("call_"))))
                        .put("type", "function").put("function", JSONObject().put("name", item.optString("name"))
                            .put("arguments", item.optString("arguments", "{}"))))
                }
                type == "function_call_output" -> {
                    flush()
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", item.optString("call_id"))
                        .put("content", item.opt("output")?.toString().orEmpty()))
                }
                role == "assistant" -> {
                    flush()
                    pendingText = responseContent(item.opt("content"), output = true).toString()
                }
                role.isNotEmpty() -> {
                    flush()
                    val mappedRole = if (role == "developer") "system" else role
                    messages.put(JSONObject().put("role", mappedRole).put("content", responseContent(item.opt("content"), output = false)))
                }
            }
        }
        flush()
    }

    private fun responseContent(value: Any?, output: Boolean): Any {
        if (value is String) return value
        val blocks = value as? JSONArray ?: return value?.toString().orEmpty()
        val texts = StringBuilder()
        val images = JSONArray()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "input_text", "text", "output_text" -> texts.append(block.optString("text"))
                "input_image" -> if (!output) {
                    var url = when (val raw = block.opt("image_url")) {
                        is String -> raw
                        is JSONObject -> raw.optString("url")
                        else -> ""
                    }
                    if (url.isEmpty()) {
                        val image = block.optJSONObject("image")
                        if (image != null && image.optString("data").isNotEmpty())
                            url = "data:${image.optString("media_type", "image/png")};base64,${image.optString("data")}" 
                    }
                    if (url.isNotEmpty()) images.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                }
            }
        }
        if (images.length() == 0) return texts.toString()
        val out = JSONArray()
        if (texts.isNotEmpty()) out.put(JSONObject().put("type", "text").put("text", texts.toString()))
        for (i in 0 until images.length()) out.put(images.get(i))
        return out
    }

    private fun blockText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val block = value.optJSONObject(i) ?: continue
                if (block.optString("type") in setOf("text", "input_text", "output_text")) append(block.optString("text"))
            }
        }
        else -> ""
    }

    private fun copy(from: JSONObject, to: JSONObject, vararg keys: String) {
        for (key in keys) if (from.has(key) && !from.isNull(key)) to.put(key, from.get(key))
    }

    private fun isEmpty(value: Any?): Boolean = value == null || value == JSONObject.NULL || value == "" ||
        (value is JSONArray && value.length() == 0) || (value is JSONObject && value.length() == 0)

    internal fun randomId(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "").take(24)
    internal fun jsonString(value: Any): String = when (value) {
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}

/** Collects one OpenAI Chat SSE stream into a stable non-stream response and telemetry. */
class ChatStreamCollector(private var model: String) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = linkedMapOf<Int, JSONObject>()
    var finishReason: String = "stop"
        private set
    var usage: JSONObject? = null
        private set

    fun feed(data: String) {
        if (data == "[DONE]") return
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (chunk.optString("model").isNotEmpty()) model = chunk.optString("model")
        chunk.optJSONObject("usage")?.let { usage = it }
        val choices = chunk.optJSONArray("choices") ?: JSONArray()
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            if (choice.optString("finish_reason").isNotEmpty()) finishReason = choice.optString("finish_reason")
            val delta = choice.optJSONObject("delta") ?: continue
            content.append(delta.optString("content"))
            reasoning.append(delta.optString("reasoning_content", delta.optString("reasoning")))
            val toolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
            for (j in 0 until toolCalls.length()) {
                val part = toolCalls.optJSONObject(j) ?: continue
                val index = part.optInt("index", j)
                val slot = calls.getOrPut(index) { JSONObject().put("index", index).put("type", "function")
                    .put("function", JSONObject().put("name", "").put("arguments", "")) }
                if (part.optString("id").isNotEmpty()) slot.put("id", part.optString("id"))
                val fn = part.optJSONObject("function") ?: continue
                val target = slot.getJSONObject("function")
                if (fn.optString("name").isNotEmpty()) target.put("name", fn.optString("name"))
                target.put("arguments", target.optString("arguments") + fn.optString("arguments"))
            }
        }
    }

    fun response(): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
        if (calls.isNotEmpty()) {
            val array = JSONArray()
            calls.toSortedMap().values.forEach { call -> call.remove("index"); array.put(call) }
            message.put("tool_calls", array)
        }
        val result = JSONObject().put("id", ProtocolAdapters.randomId("chatcmpl_"))
            .put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000)
            .put("model", model).put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("message", message).put("finish_reason", finishReason)))
        usage?.let { result.put("usage", it) }
        return result
    }

    fun outputText(): String = content.toString() + calls.toSortedMap().values.joinToString("") {
        val fn = it.optJSONObject("function") ?: JSONObject()
        "<tool_call:${fn.optString("name", "?")} ${fn.optString("arguments")}>"
    }
    fun reasoningText(): String = reasoning.toString()
}

interface NativeStreamConverter {
    fun feed(data: String): String
    fun finish(): String
    fun nonStreamResponse(): JSONObject
}

class AnthropicStreamConverter(private var model: String) : NativeStreamConverter {
    private val id = ProtocolAdapters.randomId("msg_")
    private val text = StringBuilder()
    private val tools = linkedMapOf<Int, ToolState>()
    private var started = false
    private var textOpen = false
    private var textIndex = -1
    private var nextIndex = 0
    private var finishReason = "stop"
    private var usage: JSONObject? = null

    override fun feed(data: String): String {
        if (data == "[DONE]") return ""
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return ""
        val out = StringBuilder()
        if (chunk.optString("model").isNotEmpty()) model = chunk.optString("model")
        if (!started) {
            out.append(event("message_start", JSONObject().put("message", JSONObject().put("id", id)
                .put("type", "message").put("role", "assistant").put("content", JSONArray()).put("model", model)
                .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0)))))
            started = true
        }
        chunk.optJSONObject("usage")?.let { usage = it }
        val choices = chunk.optJSONArray("choices") ?: JSONArray()
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val delta = choice.optJSONObject("delta") ?: JSONObject()
            val value = delta.optString("content")
            if (value.isNotEmpty()) {
                if (!textOpen) {
                    textIndex = nextIndex++
                    out.append(event("content_block_start", JSONObject().put("index", textIndex)
                        .put("content_block", JSONObject().put("type", "text").put("text", ""))))
                    textOpen = true
                }
                text.append(value)
                out.append(event("content_block_delta", JSONObject().put("index", textIndex)
                    .put("delta", JSONObject().put("type", "text_delta").put("text", value))))
            }
            val toolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
            for (j in 0 until toolCalls.length()) {
                val part = toolCalls.optJSONObject(j) ?: continue
                val index = part.optInt("index", j)
                val state = tools.getOrPut(index) { ToolState(blockIndex = nextIndex++) }
                if (part.optString("id").isNotEmpty()) state.id = part.optString("id")
                val fn = part.optJSONObject("function") ?: JSONObject()
                if (fn.optString("name").isNotEmpty()) state.name = fn.optString("name")
                if (!state.open) {
                    if (state.id.isEmpty()) state.id = ProtocolAdapters.randomId("call_")
                    out.append(event("content_block_start", JSONObject().put("index", state.blockIndex)
                        .put("content_block", JSONObject().put("type", "tool_use").put("id", state.id)
                            .put("name", state.name).put("input", JSONObject()))))
                    state.open = true
                }
                val args = fn.optString("arguments")
                if (args.isNotEmpty()) {
                    state.args.append(args)
                    out.append(event("content_block_delta", JSONObject().put("index", state.blockIndex)
                        .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args))))
                }
            }
            if (choice.optString("finish_reason").isNotEmpty()) finishReason = choice.optString("finish_reason")
        }
        return out.toString()
    }

    override fun finish(): String {
        val out = StringBuilder()
        if (!started) feed(JSONObject().put("choices", JSONArray()).toString()).also { out.append(it) }
        if (textOpen) { out.append(event("content_block_stop", JSONObject().put("index", textIndex))); textOpen = false }
        tools.values.forEach { if (it.open) { out.append(event("content_block_stop", JSONObject().put("index", it.blockIndex))); it.open = false } }
        val stop = when (finishReason) { "tool_calls" -> "tool_use"; "length" -> "max_tokens"; else -> "end_turn" }
        val delta = JSONObject().put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
        val convertedUsage = usage?.let { JSONObject().put("input_tokens", it.optLong("prompt_tokens", it.optLong("input_tokens")))
            .put("output_tokens", it.optLong("completion_tokens", it.optLong("output_tokens"))) }
        out.append(event("message_delta", JSONObject().put("delta", delta).put("usage", convertedUsage ?: JSONObject.NULL)))
        out.append(event("message_stop", JSONObject()))
        return out.toString()
    }

    override fun nonStreamResponse(): JSONObject {
        val blocks = JSONArray()
        if (text.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", text.toString()))
        tools.toSortedMap().values.forEach { state ->
            val input = runCatching { JSONObject(state.args.toString()) }.getOrElse { state.args.toString() }
            blocks.put(JSONObject().put("type", "tool_use").put("id", state.id).put("name", state.name).put("input", input))
        }
        val stop = when (finishReason) { "tool_calls" -> "tool_use"; "length" -> "max_tokens"; else -> "end_turn" }
        val result = JSONObject().put("id", id).put("type", "message").put("role", "assistant")
            .put("content", blocks).put("model", model).put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
        usage?.let { result.put("usage", JSONObject().put("input_tokens", it.optLong("prompt_tokens", it.optLong("input_tokens")))
            .put("output_tokens", it.optLong("completion_tokens", it.optLong("output_tokens")))) }
        return result
    }

    private fun event(type: String, fields: JSONObject): String {
        fields.put("type", type)
        return "event: $type\ndata: ${fields}\n\n"
    }

    private data class ToolState(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), val blockIndex: Int, var open: Boolean = false)
}

class ResponsesStreamConverter(private var model: String) : NativeStreamConverter {
    private val responseId = ProtocolAdapters.randomId("resp_")
    private val messageId = ProtocolAdapters.randomId("msg_")
    private val createdAt = System.currentTimeMillis() / 1000
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val tools = linkedMapOf<Int, ToolState>()
    private var created = false
    private var messageAdded = false
    private var contentAdded = false
    private var reasoningAdded = false
    private var usage: JSONObject? = null

    override fun feed(data: String): String {
        if (data == "[DONE]") return ""
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return ""
        val out = StringBuilder()
        if (chunk.optString("model").isNotEmpty()) model = chunk.optString("model")
        if (!created) {
            val response = responseObject("in_progress")
            out.append(event("response.created", JSONObject().put("response", response)))
            out.append(event("response.in_progress", JSONObject().put("response", response)))
            created = true
        }
        chunk.optJSONObject("usage")?.let { usage = it }
        val choices = chunk.optJSONArray("choices") ?: JSONArray()
        for (i in 0 until choices.length()) {
            val delta = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
            val value = delta.optString("content")
            if (value.isNotEmpty()) {
                ensureMessage(out)
                if (!contentAdded) {
                    out.append(event("response.content_part.added", JSONObject().put("output_index", 0).put("content_index", 0)
                        .put("part", JSONObject().put("type", "output_text").put("text", "").put("annotations", JSONArray()))))
                    contentAdded = true
                }
                text.append(value)
                out.append(event("response.output_text.delta", JSONObject().put("output_index", 0).put("content_index", 0).put("delta", value)))
            }
            val thought = delta.optString("reasoning_content", delta.optString("reasoning"))
            if (thought.isNotEmpty()) {
                reasoning.append(thought)
                if (!reasoningAdded) {
                    out.append(event("response.reasoning_summary_part.added", JSONObject().put("output_index", 0).put("summary_index", 0)
                        .put("part", JSONObject().put("type", "summary_text").put("text", ""))))
                    reasoningAdded = true
                }
                out.append(event("response.reasoning_summary_text.delta", JSONObject().put("output_index", 0).put("summary_index", 0).put("delta", thought)))
            }
            val calls = delta.optJSONArray("tool_calls") ?: JSONArray()
            for (j in 0 until calls.length()) {
                val part = calls.optJSONObject(j) ?: continue
                val index = part.optInt("index", j)
                val state = tools.getOrPut(index) { ToolState(outputIndex = (if (messageAdded || text.isNotEmpty()) 1 else 0) + tools.size) }
                if (part.optString("id").isNotEmpty()) state.callId = part.optString("id")
                val fn = part.optJSONObject("function") ?: JSONObject()
                if (fn.optString("name").isNotEmpty()) state.name = fn.optString("name")
                if (!state.added) {
                    if (state.callId.isEmpty()) state.callId = ProtocolAdapters.randomId("call_")
                    out.append(event("response.output_item.added", JSONObject().put("output_index", state.outputIndex).put("item", functionItem(state, "in_progress"))))
                    state.added = true
                }
                val args = fn.optString("arguments")
                if (args.isNotEmpty()) {
                    state.args.append(args)
                    out.append(event("response.function_call_arguments.delta", JSONObject().put("output_index", state.outputIndex).put("delta", args)))
                }
            }
        }
        return out.toString()
    }

    override fun finish(): String {
        val out = StringBuilder()
        if (!created) feed(JSONObject().put("choices", JSONArray()).toString()).also { out.append(it) }
        if (contentAdded) {
            out.append(event("response.output_text.done", JSONObject().put("output_index", 0).put("content_index", 0).put("text", text.toString())))
            out.append(event("response.content_part.done", JSONObject().put("output_index", 0).put("content_index", 0)
                .put("part", JSONObject().put("type", "output_text").put("text", text.toString()).put("annotations", JSONArray()))))
        }
        if (reasoningAdded) out.append(event("response.reasoning_summary_text.done", JSONObject().put("output_index", 0).put("summary_index", 0).put("text", reasoning.toString())))
        if (messageAdded) out.append(event("response.output_item.done", JSONObject().put("output_index", 0).put("item", messageItem("completed"))))
        tools.toSortedMap().values.forEach { state -> if (state.added) {
            out.append(event("response.function_call_arguments.done", JSONObject().put("output_index", state.outputIndex).put("arguments", state.args.toString())))
            out.append(event("response.output_item.done", JSONObject().put("output_index", state.outputIndex).put("item", functionItem(state, "completed"))))
        } }
        out.append(event("response.completed", JSONObject().put("response", responseObject("completed"))))
        return out.toString()
    }

    override fun nonStreamResponse(): JSONObject = responseObject("completed")

    private fun ensureMessage(out: StringBuilder) {
        if (!messageAdded) {
            out.append(event("response.output_item.added", JSONObject().put("output_index", 0).put("item", messageItem("in_progress", true))))
            messageAdded = true
        }
    }

    private fun responseObject(status: String): JSONObject {
        val output = JSONArray()
        if (messageAdded || text.isNotEmpty()) output.put(messageItem(status))
        tools.toSortedMap().values.forEach { if (it.added) output.put(functionItem(it, status)) }
        val result = JSONObject().put("id", responseId).put("object", "response").put("created_at", createdAt)
            .put("status", status).put("model", model).put("output", output).put("parallel_tool_calls", true)
        val source = usage
        if (source == null) result.put("usage", JSONObject.NULL) else result.put("usage", JSONObject()
            .put("input_tokens", source.optLong("prompt_tokens", source.optLong("input_tokens")))
            .put("input_tokens_details", JSONObject().put("cached_tokens", 0))
            .put("output_tokens", source.optLong("completion_tokens", source.optLong("output_tokens")))
            .put("output_tokens_details", JSONObject().put("reasoning_tokens", 0))
            .put("total_tokens", source.optLong("total_tokens")))
        return result
    }

    private fun messageItem(status: String, empty: Boolean = false): JSONObject {
        val content = JSONArray()
        if (!empty) content.put(JSONObject().put("type", "output_text").put("text", text.toString()).put("annotations", JSONArray()))
        return JSONObject().put("type", "message").put("id", messageId).put("status", status).put("role", "assistant").put("content", content)
    }

    private fun functionItem(state: ToolState, status: String): JSONObject = JSONObject().put("type", "function_call")
        .put("id", state.itemId).put("call_id", state.callId).put("name", state.name).put("arguments", state.args.toString()).put("status", status)

    private fun event(type: String, fields: JSONObject): String { fields.put("type", type); return "data: ${fields}\n\n" }

    private data class ToolState(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(),
        val itemId: String = ProtocolAdapters.randomId("fc_"), val outputIndex: Int, var added: Boolean = false)
}
