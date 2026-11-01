package me.rerere.ai.provider.providers.openai

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock

internal class ChatCompletionsStreamDecoder : StreamChunkDecoder {
    private val streamState = ChatCompletionsStreamState()

    /**
     * 合成工具调用 id 的 key → 已确定的调用 id。
     *
     * key 是 `index`（Provider 给了 index 时）或 `数组下标:工具名`（缺 index 时），见 [accept]。
     */
    private val toolIdsByKey = mutableMapOf<String, String>()

    /** 缺 index 时每个数组下标最近一次用到的 key，让不带名字的续写增量落在同一个调用上。 */
    private val fallbackKeyByIndex = mutableMapOf<Int, String>()

    private val reasoningDetailsByIndex = linkedMapOf<Int, JsonObject>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    /**
     * 没有响应 id 时用于合成工具调用 id 的随机后缀。
     *
     * 每条响应流一个解码器实例，所以这个值等价于"每次响应"的随机前缀。它在整个响应内保持不变，
     * 让同一调用的多条增量拿到同一个 id；不同响应（多步 agent 的每一步）则一定不同，
     * 不会像原先的 `response:tool-0` 那样在会话里跨步骤撞车。
     */
    private val fallbackSourceId = "response-${UUID.randomUUID()}"

    /** 首次需要合成 id 时定型：响应 id 已知就用它，否则用本实例的随机前缀。 */
    private var stableSourceId: String? = null

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val chunks = buildList {
            event.data.trim().split("\n").filter(String::isNotBlank).forEach { line ->
                // 部分中转会在流中夹杂 `data: null` 之类的非对象行，直接跳过
                val payload = json.parseToJsonElement(line).jsonObjectOrNull ?: return@forEach
                payload["error"]?.let { throw it.parseErrorDetail() }
                responseId = payload["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                responseModel = payload["model"]?.jsonPrimitive?.contentOrNull ?: responseModel

                payload["choices"]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull?.let { choice ->
                    (choice["delta"]?.jsonObjectOrNull ?: choice["message"]?.jsonObjectOrNull)?.let { message ->
                        val messageWithoutTools = JsonObject(message.filterKeys { it != "tool_calls" })
                        addAll(streamState.append(parseMessage(messageWithoutTools), responseId))

                        message["tool_calls"]?.jsonArrayOrNull?.forEachIndexed { fallbackIndex, element ->
                            val toolCall = element.jsonObjectOrNull ?: return@forEachIndexed
                            val index = toolCall["index"]?.jsonPrimitive?.intOrNull
                            val function = toolCall["function"]?.jsonObjectOrNull
                            val functionName = function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                            // 合成 id 的 key：有 index 就用 index（保持 `resp-1:tool-0` 的旧格式）。
                            // 缺 index 的端点每条 chunk 只带一个 tool_call，数组下标永远是 0，
                            // 只用下标会让不同工具塌成同一个 id（参数拼坏、调用静默丢失），
                            // 因此把工具名并进 key。续写增量不带名字时沿用该下标最近一次用到的 key。
                            val key = when {
                                index != null -> index.toString()
                                functionName.isNotEmpty() -> "$fallbackIndex:$functionName".also {
                                    fallbackKeyByIndex[fallbackIndex] = it
                                }
                                else -> fallbackKeyByIndex[fallbackIndex] ?: "$fallbackIndex:"
                            }
                            // 空字符串 id 与没有 id 等价：用它当 tool_call_id 会让 tool_result 对不上
                            val toolId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                                ?.also { toolIdsByKey[key] = it }
                                ?: toolIdsByKey.getOrPut(key) {
                                    "${toolIdSource()}:tool-$key"
                                }
                            addAll(streamState.append(
                                UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(UIMessagePart.Tool(
                                        toolCallId = toolId,
                                        toolName = functionName,
                                        input = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "",
                                        output = emptyList(),
                                    )),
                                ),
                                responseId,
                            ))
                        }
                    }
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                }
                parseUsage(payload["usage"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            }
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> = finish()

    /**
     * 本次响应合成 id 用的稳定前缀：拿到响应 id 就用它（保持原有格式），
     * 否则用本实例的随机前缀；一旦定型不再变化，保证同一响应内 id 稳定。
     */
    private fun toolIdSource(): String =
        stableSourceId ?: (responseId?.takeIf(String::isNotBlank) ?: fallbackSourceId)
            .also { stableSourceId = it }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return streamState.finish(finishReason, responseId, responseModel)
    }

    private fun parseMessage(payload: JsonObject): UIMessage {
        val role = MessageRole.fromValueOrNull(payload["role"]?.jsonPrimitiveOrNull?.contentOrNull)
            ?: MessageRole.ASSISTANT
        val content = payload["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val reasoning = payload["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["content"]?.takeIf { it is JsonArray }?.let { array ->
                array.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull
                    ?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull
                    ?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
            }
        val reasoningMetadata = accumulateReasoningDetails(payload["reasoning_details"]?.jsonArrayOrNull)
        val images = payload["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(UIMessagePart.Reasoning(
                        reasoning = reasoning.orEmpty(),
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = reasoningMetadata,
                    ))
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    if (imageObject["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull
                        ?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(payload["annotations"]?.jsonArrayOrNull ?: JsonArray(emptyList())),
        )
    }

    private fun accumulateReasoningDetails(details: JsonArray?): JsonObject? {
        if (details == null) return null
        details.forEachIndexed { fallbackIndex, element ->
            val incoming = element.jsonObjectOrNull ?: return@forEachIndexed
            val index = incoming["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
            reasoningDetailsByIndex[index] = mergeReasoningDetail(reasoningDetailsByIndex[index], incoming)
        }
        return OpenRouterReasoningMetadata(
            reasoningDetails = JsonArray(reasoningDetailsByIndex.toSortedMap().values.toList()),
        ).toMetadata()
    }

    private fun mergeReasoningDetail(existing: JsonObject?, incoming: JsonObject): JsonObject = buildJsonObject {
        existing?.forEach { (key, value) -> put(key, value) }
        incoming.forEach { (key, value) ->
            val delta = value.jsonPrimitiveOrNull?.contentOrNull
            if (existing != null && key in REASONING_DETAIL_DELTA_FIELDS && delta != null) {
                val previous = existing[key]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                put(key, JsonPrimitive(previous + delta))
            } else {
                put(key, value)
            }
        }
    }

    private fun parseAnnotations(array: JsonArray): List<UIMessageAnnotation> = array.mapNotNull { element ->
        val annotation = element.jsonObjectOrNull ?: return@mapNotNull null
        val type = annotation["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
        when (type) {
            "url_citation" -> UIMessageAnnotation.UrlCitation(
                title = annotation["url_citation"]?.jsonObjectOrNull
                    ?.get("title")?.jsonPrimitive?.contentOrNull ?: "",
                url = annotation["url_citation"]?.jsonObjectOrNull
                    ?.get("url")?.jsonPrimitive?.contentOrNull ?: "",
            )
            else -> error("unknown annotation type: $type")
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["prompt_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0,
        )
    }

    private class ChatCompletionsStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null
        private val openToolIds = linkedSetOf<String>()
        private var lastToolId: String? = null

        fun append(message: UIMessage, sourceId: String?): List<StreamChunk> = buildList {
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                        addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
                        val id = textId ?: nextId(sourceId, "text").also {
                            textId = it; add(StreamChunk.TextStart(it))
                        }
                        add(StreamChunk.TextDelta(id, part.text))
                    }
                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText()); addAll(closeImage()); addAll(closeTools())
                        val id = reasoningId ?: nextId(sourceId, "reasoning").also {
                            reasoningId = it; add(StreamChunk.ReasoningStart(it, part.metadata))
                        }
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                    }
                    is UIMessagePart.Tool -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                        val id = part.toolCallId.ifBlank { lastToolId ?: nextId(sourceId, "tool") }
                        var nameDelta = part.toolName
                        if (openToolIds.add(id)) {
                            nameDelta = ""
                            add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                        }
                        lastToolId = id
                        if (nameDelta.isNotEmpty() || part.input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(id, nameDelta, part.input, part.metadata))
                        }
                    }
                    is UIMessagePart.Image -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeTools())
                        val id = imageId ?: nextId(sourceId, "image").also {
                            imageId = it; add(StreamChunk.ImageStart(it, metadata = part.metadata))
                        }
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                    }
                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        fun finish(reason: String?, responseId: String?, model: String?): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()
        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun closeTools() = openToolIds.toList().map { StreamChunk.ToolCallEnd(it) }.also {
            openToolIds.clear(); lastToolId = null
        }
        private fun nextId(sourceId: String?, kind: String): String =
            "${sourceId?.takeIf(String::isNotBlank)?.let { "$it:" }.orEmpty()}$kind-${++sequence}"
    }

    private companion object {
        val REASONING_DETAIL_DELTA_FIELDS = setOf("text", "summary", "data", "signature")
    }
}
