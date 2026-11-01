package me.rerere.ai.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.util.json

/** Object key order is not significant; arrays and primitive representations stay intact. */
internal fun canonicalToolArguments(input: String): String? = runCatching {
    canonicalToolJson(json.parseToJsonElement(input.ifBlank { "{}" })).toString()
}.getOrNull()

internal fun canonicalToolJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalToolJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalToolJson))
    else -> value
}

/** Only local, explicit metadata permits overlapping calls. Unknown tools remain barriers. */
internal fun ToolExecutionMode.allowsParallelExecution() = this != ToolExecutionMode.SERIAL
