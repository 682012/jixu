package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart

private const val MAX_BATCH_CALLS = 16

/** One model round trip for a preplanned batch. All arguments/permissions are checked before effects. */
fun createToolBatch(tools: List<Tool>): Tool {
    val catalog = tools.filter { it.name != "tool_batch" && it.name != "ask_user" }.associateBy { it.name }
    fun parse(input: JsonElement): List<BatchCall> {
        val items = (input as? JsonObject)?.get("calls") as? JsonArray
            ?: error("calls must be an array of {name, arguments} objects")
        require(items.size in 1..MAX_BATCH_CALLS) { "calls must contain 1..$MAX_BATCH_CALLS items" }
        return items.mapIndexed { index, item ->
            val objectInput = item as? JsonObject ?: error("calls[$index] must be an object")
            val name = (objectInput["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("calls[$index].name must be a string")
            val tool = catalog[name] ?: error("Tool '$name' is not available for batching; nothing was executed")
            val arguments = when (val args = objectInput["arguments"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> args
                else -> error("calls[$index].arguments must be an object; nothing was executed")
            }
            BatchCall(index, tool, arguments)
        }
    }
    return Tool(
        name = "tool_batch",
        description = "Execute 1..16 already planned tool calls in one round trip. Each item has name and arguments. " +
            "Use only tools currently advertised and arguments already known; dependent results require a later call. " +
            "Independent reads overlap (max 8); writes/unknown tools remain ordered barriers. " +
            "All nested approvals apply. Results preserve call order; do not batch ask_user or tool_batch itself.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("calls", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", MAX_BATCH_CALLS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("arguments", buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", true)
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    })
                })
            }, required = listOf("calls"))
        },
        // Invalid input fails during execution without an approval dead-end. No member has run yet.
        needsApproval = { input ->
            runCatching { parse(input) }.getOrNull()?.any { it.tool.needsApproval(it.arguments) } ?: false
        },
        execute = { input -> executeBatch(parse(input)) },
    )
}

private data class BatchCall(val index: Int, val tool: Tool, val arguments: JsonObject)

private suspend fun executeBatch(calls: List<BatchCall>): List<UIMessagePart> {
    val outputs = arrayOfNulls<List<UIMessagePart>>(calls.size)
    var from = 0
    while (from < calls.size) {
        if (calls[from].tool.executionMode == ToolExecutionMode.SERIAL) {
            outputs[from] = executeBatchCall(calls[from])
            from++
            continue
        }
        var until = from + 1
        while (until < calls.size && calls[until].tool.executionMode.allowsParallelExecution()) until++
        val group = calls.subList(from, until)
        val grouped = LinkedHashMap<String, MutableList<BatchCall>>()
        group.forEach { call ->
            val key = if (call.tool.executionMode == ToolExecutionMode.PARALLEL_DEDUPLICATED) {
                "${call.tool.name}\n${canonicalToolJson(call.arguments)}"
            } else "unique:${call.index}"
            grouped.getOrPut(key) { mutableListOf() }.add(call)
        }
        coroutineScope {
            val permits = Semaphore(8)
            grouped.values.map { members -> async {
                permits.withPermit {
                    val result = executeBatchCall(members.first())
                    members.forEach { outputs[it.index] = result }
                }
            } }.awaitAll()
        }
        from = until
    }
    val text = buildJsonObject {
        put("results", buildJsonArray {
            calls.forEach { call -> add(buildJsonObject {
                put("index", call.index)
                put("name", call.tool.name)
                put("output", buildJsonArray {
                    outputs[call.index].orEmpty().filterIsInstance<UIMessagePart.Text>()
                        .forEach { add(JsonPrimitive(it.text)) }
                })
                val nonText = outputs[call.index].orEmpty().count { it !is UIMessagePart.Text }
                if (nonText > 0) put("attachedParts", nonText)
            }) }
        })
    }.toString()
    return listOf(UIMessagePart.Text(text)) + outputs.flatMap { it.orEmpty().filterNot { part -> part is UIMessagePart.Text } }
}

private suspend fun executeBatchCall(call: BatchCall): List<UIMessagePart> = try {
    call.tool.execute(call.arguments).ifEmpty { listOf(UIMessagePart.Text("(no output)")) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    listOf(UIMessagePart.Text(buildJsonObject { put("error", "[${e.javaClass.simpleName}] ${e.message}") }.toString()))
}
