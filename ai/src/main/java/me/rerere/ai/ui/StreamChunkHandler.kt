package me.rerere.ai.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.util.json
import kotlin.time.Clock

/**
 * 将 Provider 产生的通用流事件合并为一条 [UIMessage]。
 *
 * 文本、推理和图片可能交错到达，因此不能只更新最后一个 part。本类会在收到对应的 `Start`
 * 事件时，记录事件 id 在 [UIMessage.parts] 中的位置；后续 `Delta`、`Snapshot` 和 `End`
 * 事件再通过同一 id 定位并更新该 part。
 *
 * ## 工具调用的 id 隔离
 *
 * 工具调用不能用 Provider 给的事件 id 直接当作 [UIMessagePart.Tool.toolCallId] 去消息里查找，
 * 因为一个实例只代表一次响应（多步工具调用里的一步），而它拿到的消息可能已经包含之前步骤
 * 生成的、已经执行过的工具 part。部分中转或本地 OpenAI 兼容端点不在流里返回响应 id，
 * 更不在 tool_calls 里返回调用 id，于是每一步的合成都可能得到同一个 id。若按 id 查找旧 part：
 *
 * - `Start` 会因为"id 已存在"被吞掉，不创建新 part；
 * - `Delta` 会把本步的入参拼到上一步已执行 part 的输入末尾，`output` 与 `isExecuted` 保持不变；
 * - 结果是本步的调用静默消失，模型只收到上一次的调用和结果。
 *
 * 因此本类为每个事件 id 维护一条**实例内**映射：[StreamChunk.ToolCallStart] 只在本实例第一次
 * 见到该 id 时创建 part，此时若 provider id 在消息里（之前步骤留下的 part）已被占用，就改用带
 * `#n` 后缀的本地唯一 id，之后同一 id 的 `Delta`/`End` 都作用在这个新 part 上。工具名与入参仍来自
 * Provider，真实且不重复的 Provider id 原样保留，供 wire 协议里的 `tool_calls[].id` 与
 * `tool_result.tool_call_id` 对应（两者都指同一个 part 的 id，因此仍然一致）。
 * 同一实例内重复的 `Start` 视为重复事件：不会创建第二个 part，也不会把工具名拼两遍，
 * 因此同一批里不会出现两个 id 相同的调用被各执行一次。
 *
 * [handle] 不会修改传入的消息列表，而是返回包含更新后消息的新列表。如果列表末尾不是助手
 * 消息，第一个事件会自动创建一条空的助手消息。收到 [StreamChunk.Finish] 后，会标记消息完成
 * 并清除内部状态。
 *
 * ## 工作过程
 *
 * 1. 接收一个 [StreamChunk]，检查消息列表非空。
 * 2. 如果列表末尾不是助手消息，创建一条带有当前 [model] id 的空助手消息。
 * 3. 根据事件类型更新助手消息：
 *    - `TextStart/Delta/End`：创建、追加并结束文本 part。
 *    - `ReasoningStart/Delta/End`：创建、追加并标记推理 part 的完成时间。
 *    - `ToolCallStart/Delta/End`：为本步的事件 id 分配消息里的唯一 toolCallId，创建工具 part，
 *      并逐步拼接工具名和输入参数。
 *    - `ServerToolStart/InputDelta/InputEnd/End`：追踪服务端工具的 JSON 输入、结果和状态。
 *    - `ImageStart/Delta/End`：创建图片 part，并逐步追加 Base64 数据。
 *    - `ImageSnapshot`：用最新的完整 Base64 快照替换同 id 图片的旧数据。
 *    - `Annotations`：追加并去重消息注解。
 *    - `Usage`：将本次用量合并到消息已有的 Token 用量中。
 *    - `Finish`：设置消息完成时间，结束尚未关闭的推理 part，并清空事件索引。
 * 4. 使用 [UIMessage.copy] 生成更新后的助手消息，将其替换到列表末尾并返回新列表。
 *
 * 一段文本流的典型调用顺序如下：
 *
 * ```
 * TextStart(id) -> TextDelta(id, ...) -> TextDelta(id, ...) -> TextEnd(id) -> Finish
 * ```
 *
 * [StreamChunk.Finish] 只代表响应流正常结束，并不保证一定到达。网络错误、协议解析失败或上层取消
 * Flow 时，流可能直接异常结束。此时已经合并的内容仍然保留，但消息的 `finishedAt` 可能为空，尚未
 * 收到 `ReasoningEnd` 的推理 part 也不会由本类自动结束。调用方应在 Flow 的完成或异常处理中执行
 * 必要的 UI 收尾，并丢弃当前 handler；不要将它复用于下一条响应流。
 *
 * 该类保存着一次响应流的合并状态，不是无状态转换器。每条并发响应流都必须使用独立实例，且
 * 事件应按 Provider 产生的顺序交给同一实例处理。
 */
class StreamChunkHandler(private val model: Model? = null) {
    // Map 的值是对应 part 在当前助手消息 parts 列表中的下标。
    private val textPartIndexes = mutableMapOf<String, Int>()
    private val reasoningPartIndexes = mutableMapOf<String, Int>()
    private val imagePartIndexes = mutableMapOf<String, Int>()
    private val serverToolInputBuffers = mutableMapOf<String, StringBuilder>()

    /**
     * 本实例（一次响应 = 一个生成步骤）内 Provider 工具事件 id 到消息里真实 toolCallId 的映射。
     *
     * 消息可能已经带着之前步骤的 part，所以这里绝不按 Provider id 去 `parts` 里找"已有的调用"。
     */
    private val toolCallSlots = mutableMapOf<String, ToolCallSlot>()

    /**
     * 将一个 [chunk] 合并进消息列表末尾的助手消息，并返回新的消息列表。
     *
     * @throws IllegalArgumentException 当 [messages] 为空时抛出
     */
    fun handle(messages: List<UIMessage>, chunk: StreamChunk): List<UIMessage> {
        require(messages.isNotEmpty()) { "messages must not be empty" }

        val targetMessages = if (messages.last().role != MessageRole.ASSISTANT) {
            messages + UIMessage(modelId = model?.id, role = MessageRole.ASSISTANT, parts = emptyList())
        } else {
            messages
        }
        val updatedMessage = append(targetMessages.last(), chunk)
        return targetMessages.dropLast(1) + updatedMessage
    }

    private fun append(message: UIMessage, chunk: StreamChunk): UIMessage = with(message) {
        when (chunk) {
            is StreamChunk.TextStart -> {
                if (chunk.id in textPartIndexes) {
                    this
                } else {
                    copy(parts = parts + UIMessagePart.Text("", chunk.metadata)).also {
                        textPartIndexes[chunk.id] = parts.size
                    }
                }
            }
            is StreamChunk.TextDelta -> {
                val index = textPartIndexes[chunk.id]
                // 容忍 Provider 未发送 Start：首次收到 Delta 时直接创建对应 part。
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Text) {
                    copy(parts = parts + UIMessagePart.Text(chunk.text, chunk.metadata)).also {
                        textPartIndexes[chunk.id] = parts.size
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val text = get(index) as UIMessagePart.Text
                        set(
                            index,
                            text.copy(
                                text = text.text + chunk.text,
                                metadata = chunk.metadata ?: text.metadata,
                            )
                        )
                    })
                }
            }

            is StreamChunk.TextEnd -> this.also { textPartIndexes.remove(chunk.id) }
            is StreamChunk.ReasoningStart -> {
                if (chunk.id in reasoningPartIndexes) {
                    this
                } else {
                    copy(parts = parts + UIMessagePart.Reasoning(
                        reasoning = "",
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )).also { reasoningPartIndexes[chunk.id] = parts.size }
                }
            }

            is StreamChunk.ReasoningDelta -> {
                val index = reasoningPartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) {
                    copy(parts = parts + UIMessagePart.Reasoning(
                        reasoning = chunk.text,
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )).also { reasoningPartIndexes[chunk.id] = parts.size }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val reasoning = get(index) as UIMessagePart.Reasoning
                        set(index, reasoning.copy(
                            reasoning = reasoning.reasoning + chunk.text,
                            metadata = chunk.metadata ?: reasoning.metadata,
                            reasoningType = chunk.reasoningType,
                        ))
                    })
                }
            }

            is StreamChunk.ReasoningEnd -> {
                val index = reasoningPartIndexes.remove(chunk.id)
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) {
                    this
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val reasoning = get(index) as UIMessagePart.Reasoning
                        set(index, reasoning.copy(
                            finishedAt = Clock.System.now(),
                            metadata = chunk.metadata ?: reasoning.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ToolCallStart -> {
                // 同一实例内重复的 Start（例如 Provider 在文本之后续写同一个 tool call）
                // 是重复事件：既不新建 part，也不重置工具名，避免后续 Delta 把名字拼两遍。
                // 这里只看"本步是否已经见过这个 id"，不能用 parts 里有没有同 id 的 part 来判断，
                // 否则消息里之前步骤留下的、已经执行过的 part 会把本步的调用吞掉。
                if (chunk.id in toolCallSlots) {
                    this
                } else {
                    val toolCallId = allocateToolCallId(chunk.id, parts)
                    copy(parts = parts + UIMessagePart.Tool(
                        toolCallId = toolCallId,
                        toolName = chunk.toolName,
                        input = "",
                        metadata = chunk.metadata,
                    )).also { toolCallSlots[chunk.id] = ToolCallSlot(toolCallId) }
                }
            }

            is StreamChunk.ToolCallDelta -> {
                val slot = toolCallSlots[chunk.id]
                when {
                    slot != null -> copy(parts = parts.map { part ->
                        // 工具调用可以并行生成，通过分配到的 toolCallId 而不是 part 位置识别目标。
                        if (part is UIMessagePart.Tool && part.toolCallId == slot.toolCallId) {
                            part.copy(
                                toolName = part.toolName + chunk.toolNameDelta,
                                input = part.input + chunk.inputDelta,
                                metadata = chunk.metadata ?: part.metadata,
                            )
                        } else {
                            part
                        }
                    })

                    chunk.toolNameDelta.isEmpty() && chunk.inputDelta.isEmpty() -> this

                    // 容忍 Provider 未发送 Start：首次收到 Delta 时按新调用建立 part。
                    else -> {
                        val toolCallId = allocateToolCallId(chunk.id, parts)
                        copy(parts = parts + UIMessagePart.Tool(
                            toolCallId = toolCallId,
                            toolName = chunk.toolNameDelta,
                            input = chunk.inputDelta,
                            metadata = chunk.metadata,
                        )).also { toolCallSlots[chunk.id] = ToolCallSlot(toolCallId) }
                    }
                }
            }

            is StreamChunk.ToolCallEnd -> this
            is StreamChunk.ToolCallOutput -> {
                // 只写本实例分配到的那个 part；不做保底匹配，避免把输出写到之前步骤的调用上。
                val toolCallId = toolCallSlots[chunk.id]?.toolCallId ?: chunk.id
                copy(parts = parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                        part.copy(output = listOf(UIMessagePart.Text(chunk.output)))
                    } else {
                        part
                    }
                })
            }
            is StreamChunk.ServerToolStart -> {
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = chunk.input,
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = chunk.metadata,
                    ))
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val tool = get(index) as UIMessagePart.ServerTool
                        set(index, tool.copy(
                            toolName = chunk.toolName.ifBlank { tool.toolName },
                            input = chunk.input ?: tool.input,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        ))
                    })
                }
            }

            is StreamChunk.ServerToolInputDelta -> {
                val buffer = serverToolInputBuffers.getOrPut(chunk.id) { StringBuilder() }
                buffer.append(chunk.inputDelta)
                updateServerTool(chunk.id) { tool ->
                    tool.copy(metadata = mergeMetadata(tool.metadata, chunk.metadata))
                }
            }

            is StreamChunk.ServerToolInputEnd -> {
                val input = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                if (input == null) this else updateServerTool(chunk.id) { it.copy(input = input) }
            }

            is StreamChunk.ServerToolEnd -> {
                val bufferedInput = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = "",
                        input = chunk.input ?: bufferedInput,
                        output = chunk.output,
                        status = chunk.status,
                        metadata = chunk.metadata,
                    ))
                } else {
                    updateServerTool(chunk.id) { tool ->
                        tool.copy(
                            input = chunk.input ?: bufferedInput ?: tool.input,
                            output = chunk.output ?: tool.output,
                            status = chunk.status,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        )
                    }
                }
            }
            is StreamChunk.ImageStart -> {
                if (chunk.id in imagePartIndexes) {
                    this
                } else {
                    copy(parts = parts + UIMessagePart.Image(
                        url = "data:${chunk.mimeType};base64,",
                        metadata = chunk.metadata,
                    )).also { imagePartIndexes[chunk.id] = parts.size }
                }
            }

            is StreamChunk.ImageDelta -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(parts = parts + UIMessagePart.Image(chunk.data, chunk.metadata)).also {
                        imagePartIndexes[chunk.id] = parts.size
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        set(index, image.copy(
                            url = image.url + chunk.data,
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageSnapshot -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(
                        parts = parts + UIMessagePart.Image(
                            url = "data:image/png;base64,${chunk.data}",
                            metadata = chunk.metadata,
                        )
                    ).also { imagePartIndexes[chunk.id] = parts.size }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        // Snapshot 是一张完整的可渲染图片，只替换 data URL 的数据部分；
                        // 与 ImageDelta 不同，它不会把数据追加到上一帧之后。
                        val dataUrlPrefix = image.url.substringBefore(",").takeIf { it.startsWith("data:") }
                            ?: "data:image/png;base64"
                        set(index, image.copy(
                            url = "$dataUrlPrefix,${chunk.data}",
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageEnd -> this.also { imagePartIndexes.remove(chunk.id) }
            is StreamChunk.Annotations -> copy(annotations = (annotations + chunk.annotations).distinct())
            is StreamChunk.Usage -> copy(usage = usage.merge(chunk.usage))
            is StreamChunk.Finish -> copy(
                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            ).finishReasoning().also {
                // Finish 同时结束尚未显式结束的 reasoning，并释放本次响应流的索引状态。
                textPartIndexes.clear()
                reasoningPartIndexes.clear()
                imagePartIndexes.clear()
                serverToolInputBuffers.clear()
                toolCallSlots.clear()
            }
        }
    }

    /**
     * 为 Provider 的工具事件 id 分配消息里唯一的 [UIMessagePart.Tool.toolCallId]。
     *
     * Provider id 未被占用时原样保留（wire 协议要求 `tool_calls[].id` 与
     * `tool_result.tool_call_id` 对应，原样保留最省事）；已被之前步骤或本实例占用时
     * 改用 `base#n` 形式的新 id，绝不覆盖已经存在的 part。
     */
    private fun allocateToolCallId(providerId: String, parts: List<UIMessagePart>): String {
        val used = HashSet<String>()
        parts.forEach { part ->
            if (part is UIMessagePart.Tool) used += part.toolCallId
        }
        toolCallSlots.values.forEach { used += it.toolCallId }

        val base = providerId.ifBlank { DEFAULT_LOCAL_TOOL_CALL_ID }
        if (base !in used) return base
        var suffix = 2
        while ("$base#$suffix" in used) suffix++
        return "$base#$suffix"
    }

    /** 一次响应内某个 Provider 工具事件 id 到本地 toolCallId 的映射。 */
    private class ToolCallSlot(val toolCallId: String)

    private companion object {
        /** Provider 连事件 id 都没给时使用的本地前缀。 */
        const val DEFAULT_LOCAL_TOOL_CALL_ID = "tool"
    }

    private fun UIMessage.updateServerTool(
        id: String,
        transform: (UIMessagePart.ServerTool) -> UIMessagePart.ServerTool,
    ): UIMessage = copy(parts = parts.map { part ->
        if (part is UIMessagePart.ServerTool && part.toolCallId == id) transform(part) else part
    })
}

private fun parseServerToolJson(value: String) = runCatching {
    json.parseToJsonElement(value)
}.getOrElse { JsonPrimitive(value) }

private fun mergeMetadata(old: JsonObject?, new: JsonObject?): JsonObject? = when {
    old == null -> new
    new == null -> old
    else -> JsonObject(old + new)
}

fun List<UIMessage>.handleTextGenerationResult(
    result: TextGenerationResult,
    model: Model? = null,
): List<UIMessage> {
    require(isNotEmpty()) { "messages must not be empty" }
    val incoming = result.message.copy(
        modelId = model?.id,
        usage = result.usage,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    ).finishReasoning()
    return if (last().role != incoming.role) {
        this + incoming
    } else {
        dropLast(1) + last().appendMessage(incoming).copy(
            modelId = model?.id ?: last().modelId,
            usage = last().usage.merge(result.usage ?: TokenUsage()),
            finishedAt = incoming.finishedAt,
        ).finishReasoning()
    }
}

private fun UIMessage.appendMessage(delta: UIMessage): UIMessage {
    var newParts = delta.parts.fold(parts) { acc, deltaPart ->
        when (deltaPart) {
            is UIMessagePart.Text -> {
                if (deltaPart.text.isEmpty()) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (lastPart is UIMessagePart.Text) {
                        acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                    } else {
                        acc + deltaPart
                    }
                }
            }

            // 非流式解析已经产出完整可渲染的 URL(data URI 或 http)，
            // 这里每张图片都是独立的完整图片，不能拼接 URL 或补 data 前缀。
            is UIMessagePart.Image -> acc + deltaPart

            is UIMessagePart.Reasoning -> {
                if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (lastPart is UIMessagePart.Reasoning &&
                        lastPart.reasoningType == deltaPart.reasoningType
                    ) {
                        acc.dropLast(1) + UIMessagePart.Reasoning(
                            reasoning = lastPart.reasoning + deltaPart.reasoning,
                            createdAt = lastPart.createdAt,
                            finishedAt = null,
                            metadata = deltaPart.metadata ?: lastPart.metadata,
                            reasoningType = lastPart.reasoningType,
                        )
                    } else {
                        acc + deltaPart
                    }
                }
            }

            is UIMessagePart.Tool -> {
                if (deltaPart.toolCallId.isBlank()) {
                    val lastTool = acc.lastOrNull { it is UIMessagePart.Tool } as? UIMessagePart.Tool
                    if (lastTool != null) {
                        acc.map { part -> if (part === lastTool) part.merge(deltaPart) else part }
                    } else {
                        acc + deltaPart.copy()
                    }
                } else {
                    val existingPart = acc.find {
                        it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                    } as? UIMessagePart.Tool
                    if (existingPart == null) {
                        acc + deltaPart.copy()
                    } else {
                        acc.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                part.merge(deltaPart)
                            } else {
                                part
                            }
                        }
                    }
                }
            }

            is UIMessagePart.ServerTool -> {
                val existingPart = acc.find {
                    it is UIMessagePart.ServerTool && it.toolCallId == deltaPart.toolCallId
                } as? UIMessagePart.ServerTool
                if (existingPart == null) {
                    acc + deltaPart
                } else {
                    acc.map { part ->
                        if (part is UIMessagePart.ServerTool && part.toolCallId == deltaPart.toolCallId) {
                            part.copy(
                                toolName = deltaPart.toolName.ifBlank { part.toolName },
                                input = deltaPart.input ?: part.input,
                                output = deltaPart.output ?: part.output,
                                status = deltaPart.status,
                                metadata = mergeMetadata(part.metadata, deltaPart.metadata),
                            )
                        } else {
                            part
                        }
                    }
                }
            }

            else -> acc
        }
    }

    if (parts.filterIsInstance<UIMessagePart.Reasoning>().isNotEmpty() &&
        delta.parts.filterIsInstance<UIMessagePart.Reasoning>().isEmpty()
    ) {
        newParts = newParts.map { part ->
            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                part.copy(finishedAt = Clock.System.now())
            } else {
                part
            }
        }
    }

    return copy(
        parts = newParts,
        annotations = delta.annotations.ifEmpty { annotations },
    )
}
