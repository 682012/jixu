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

/**
 * 不能出现在批内的工具名：
 * - `tool_batch`：批自身，批内批会形成递归并绕过上限/顺序语义；
 * - `ask_user`：交互式工具，答案要等用户，批内无法表达"等一半"；
 * - `run_script`：脚本执行工具，参数是自由文本脚本，批内一次性提交无法逐条审批与审计。
 *
 * 这里按字面名字排除（ai 模块不依赖 app 模块的工具常量）；新增同类工具时必须同步这个集合。
 */
private val BATCH_EXCLUDED_TOOL_NAMES = setOf("tool_batch", "ask_user", "run_script")

/** One model round trip for a preplanned batch. All arguments/permissions are checked before effects. */
fun createToolBatch(tools: List<Tool>): Tool {
    val catalog = tools.filter { it.name !in BATCH_EXCLUDED_TOOL_NAMES }.associateBy { it.name }
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
            "All nested approvals apply. Results preserve call order; member failures come back as an error field " +
            "and the remaining calls still run. Do not batch ask_user, run_script or tool_batch itself.",
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
        // 两类失败要分开：解析失败（输入非法）不会执行任何成员，执行阶段 parse 会再抛一次，
        // 变成错误输出且没有副作用；而成员审批判定失败意味着"不知道要不要审批"，
        // 必须失败关闭（要求审批），否则一个抛异常的 needsApproval 会让整批静默放行。
        needsApproval = { input ->
            when (val calls = runCatching { parse(input) }.getOrNull()) {
                null -> false
                else -> calls.any { call ->
                    runCatching { call.tool.needsApproval(call.arguments) }.getOrDefault(true)
                }
            }
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
} catch (e: Throwable) {
    // 与 AgentLoop.runTool 一致：Error（模型生成的超大 JSON 导致的 StackOverflowError、超大输出的
    // OutOfMemoryError）也转成错误输出让模型自我修正，不要让一个成员掀掉整轮。取消仍然向上传播。
    listOf(UIMessagePart.Text(buildJsonObject { put("error", "[${e.javaClass.simpleName}] ${e.message}") }.toString()))
}
