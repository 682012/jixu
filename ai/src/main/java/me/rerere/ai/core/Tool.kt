package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
enum class ToolExecutionMode {
    /** Unknown tools and tools with side effects are ordered barriers. */
    SERIAL,
    /** Independent calls may overlap, but every call still executes. */
    PARALLEL,
    /** Identical calls may share one result within a single adjacent read batch only. */
    PARALLEL_DEDUPLICATED,
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    // Local scheduling metadata; never inferred from a tool name or exported as provider schema.
    val executionMode: ToolExecutionMode = ToolExecutionMode.SERIAL,
    // 轮询类工具（等待、查状态）重复调用且结果相同是正常的：不折叠重复输出，也不按重复调用拦截
    val repeatable: Boolean = false,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
