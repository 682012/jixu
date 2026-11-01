package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SUBAGENT_MAX_WAIT_TASKS
import me.rerere.rikkahub.data.ai.subagent.SUBAGENT_RESULT_DEFAULT_LIMIT
import me.rerere.rikkahub.data.ai.subagent.SUBAGENT_RESULT_MAX_LIMIT
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.SubagentTask
import me.rerere.rikkahub.data.ai.subagent.resolveWaitTaskIds
import me.rerere.rikkahub.data.datastore.SubagentSettings
import kotlin.uuid.Uuid

/**
 * 子代理工具组。
 *
 * [childTools] 必须是"不含 subagent_*"的工具集，否则子代理可以无限嵌套。
 */
fun createSubagentTools(
    runner: SubagentRunner,
    childTools: List<Tool>,
    settings: SubagentSettings,
    parentModelId: Uuid,
    conversationId: Uuid?,
): List<Tool> {
    // Never enumerate every provider model: large accounts can have thousands of UUIDs.
    val modelPool = settings.modelIds
    val modelHint = when {
        modelPool.isEmpty() -> "省略 model_id 使用默认模型。"
        modelPool.size <= 8 -> "可选 model_id: " + modelPool.joinToString(", ") { it.toString() }
        else -> "设置中配置了 ${modelPool.size} 个可选模型；省略 model_id 使用默认模型，具体 ID 请在设置页查看。"
    }

    fun resolveModelId(raw: String?): Uuid? {
        if (raw.isNullOrBlank()) {
            val preferred = settings.defaultModelId ?: parentModelId
            require(modelPool.isEmpty() || preferred in modelPool) { "Default model is outside the allowed subagent model pool" }
            return preferred
        }
        val id = runCatching { Uuid.parse(raw.trim()) }.getOrNull()
            ?: error("Invalid model_id: $raw")
        require(modelPool.isEmpty() || id in modelPool) { "model_id is not in the allowed subagent model pool" }
        return id
    }

    fun owned(id: String): SubagentTask {
        val task = runner.get(id) ?: error("Task not found: $id")
        require(task.conversationId == conversationId) { "Task belongs to another conversation" }
        return task
    }

    /** offset/limit：模型传字符串或数字都能用；越界与非正数在模型层夹住。 */
    fun pageLimit(params: JsonObject): Int {
        val raw = params.string("limit")?.trim()?.toIntOrNull() ?: SUBAGENT_RESULT_DEFAULT_LIMIT
        return if (raw <= 0) SUBAGENT_RESULT_DEFAULT_LIMIT else raw.coerceAtMost(SUBAGENT_RESULT_MAX_LIMIT)
    }

    fun pageOffset(params: JsonObject): Int =
        (params.string("offset")?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)

    /** 首次交付：带分页字段和这一页正文；之后同一个 task 的 wait 只给状态和读取提示。 */
    fun waitEntry(task: SubagentTask, limit: Int): JsonObject {
        if (task.isFinished && runner.isDelivered(task.id)) {
            return buildJsonObject {
                put("taskId", task.id)
                put("status", task.status.name)
                put("resultAvailable", runner.resultChars(task.id) > 0)
                put("readHint", "Result was already returned once. Read it with subagent_result(task_id=\"${task.id}\", offset=…); it is still fully readable.")
            }
        }
        val page = runner.readResult(task.id, 0, limit)
            ?: error("Task not found: ${task.id}")
        // 只有终态才算"结果已交付"；还在跑的任务下次 wait 仍应给最新内容，不能提前吞掉首次输出。
        if (task.isFinished) runner.markDelivered(task.id)
        return buildJsonObject {
            put("taskId", task.id)
            put("status", task.status.name)
            put("modelId", task.modelId?.toString() ?: "")
            put("steps", task.steps)
            put("error", task.error ?: "")
            put("resultAvailable", page.totalChars > 0)
            put("offset", page.offset)
            put("limit", page.limit)
            put("totalChars", page.totalChars)
            put("returnedChars", page.text.length)
            put("nextOffset", page.nextOffset)
            put("eof", page.eof)
            put("truncated", page.truncated)
            put("result", page.text)
            put("readHint", when {
                !task.isFinished ->
                    "Task is still running; this is partial output. Wait again or read it with subagent_result(task_id=\"${task.id}\")."
                page.eof ->
                    "This is the whole result. subagent_result(task_id=\"${task.id}\") reads it again if needed."
                else ->
                    "More output remains: subagent_result(task_id=\"${task.id}\", offset=${page.nextOffset})."
            })
        }
    }

    val run = Tool(
        name = "subagent_run",
        description = buildString {
            append("Spawn a focused subagent to work on one task in parallel. Returns a task id immediately. ")
            append("Call it multiple times to fan out independent work. ")
            append("Each task automatically posts one short completion notice (task id, status, steps, result size) into this conversation when it finishes, fails, times out or is cancelled — do not poll to detect completion. ")
            append("Read output with subagent_result, which pages long results ($SUBAGENT_RESULT_DEFAULT_LIMIT chars per page, max $SUBAGENT_RESULT_MAX_LIMIT); use subagent_wait (task_ids for up to $SUBAGENT_MAX_WAIT_TASKS tasks at once) to block until several tasks are done. ")
            append("The subagent has its own context and the same workspace/tool access (except subagent tools). ")
            append(modelHint)
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Complete, self-contained task description. The subagent cannot see this conversation.")
                    })
                    put("model_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional model id from the allowed pool")
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max model steps, default ${settings.maxSteps}")
                    })
                    put("timeout_sec", buildJsonObject {
                        put("type", "integer")
                        put("description", "Task timeout in seconds, default ${settings.timeoutSec}, 0 means no limit")
                    })
                },
                required = listOf("prompt"),
            )
        },
        needsApproval = { false },
        // 每次调用都要真的启动一个任务：并行可以，去重绝对不行。
        executionMode = ToolExecutionMode.PARALLEL,
        execute = {
            val params = it.jsonObject
            val prompt = params.string("prompt") ?: error("prompt is required")
            val modelId = resolveModelId(params.string("model_id"))
            val maxSteps = params.string("max_steps")?.toIntOrNull() ?: settings.maxSteps
            val timeoutSec = params.string("timeout_sec")?.toIntOrNull() ?: settings.timeoutSec
            val task = runner.start(
                prompt = prompt,
                tools = childTools,
                modelId = modelId,
                conversationId = conversationId,
                maxSteps = maxSteps,
                timeoutSec = timeoutSec,
            )
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("taskId", task.id)
                        put("status", task.status.name)
                        put("modelId", task.modelId?.toString() ?: "")
                    }.toString()
                )
            )
        },
    )

    val wait = Tool(
        name = "subagent_wait",
        description = buildString {
            append("Wait for one or more subagent tasks to finish, or until the wait timeout, and return their status. ")
            append("Pass task_ids (up to $SUBAGENT_MAX_WAIT_TASKS) to wait for several tasks in one call: they are awaited concurrently under a single shared timeout_sec, not one timeout each, and results come back in the order of the ids. ")
            append("The first wait for a task returns the first $SUBAGENT_RESULT_DEFAULT_LIMIT characters of its result with offset/nextOffset/totalChars/eof/truncated. ")
            append("Waiting again for a result that was already delivered returns only status, resultAvailable and a read hint — the result is not consumed, page through it with subagent_result instead of waiting again. ")
            append("Unknown task ids or ids from another conversation are rejected before waiting.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Single task id (legacy form, still supported)")
                    })
                    put("task_ids", buildJsonObject {
                        put("type", "array")
                        put("description", "Up to $SUBAGENT_MAX_WAIT_TASKS task ids to await concurrently with one shared timeout")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("timeout_sec", buildJsonObject {
                        put("type", "integer")
                        put("description", "How long to wait in total, default 600 seconds")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Chars of result to return for tasks delivered for the first time, default $SUBAGENT_RESULT_DEFAULT_LIMIT, max $SUBAGENT_RESULT_MAX_LIMIT")
                    })
                },
            )
        },
        needsApproval = { false },
        // 每次调用都推进"已交付"游标，去重会让后到的调用拿不到自己的首次输出。
        executionMode = ToolExecutionMode.PARALLEL,
        execute = {
            val params = it.jsonObject
            val rawIds = params.stringArray("task_ids")
            val single = params.string("task_id")
            val ids = resolveWaitTaskIds(rawIds, single)
            // 先校验全部归属再等待：未知 id 不能借等待去触碰别的会话。
            ids.forEach { owned(it) }
            val waitSec = params.string("timeout_sec")?.toIntOrNull() ?: 600
            val limit = pageLimit(params)
            val tasks = runner.awaitAll(ids, waitSec)
            val entries = ids.mapIndexed { index, id ->
                val task = tasks.getOrNull(index) ?: error("Task not found: $id")
                waitEntry(task, limit)
            }
            val text = if (single != null && rawIds.isNullOrEmpty()) {
                entries.single().toString()
            } else {
                buildJsonObject { put("results", JsonArray(entries)) }.toString()
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    val result = Tool(
        name = "subagent_result",
        description = buildString {
            append("Read a subagent task's result as a page. Default $SUBAGENT_RESULT_DEFAULT_LIMIT chars, max $SUBAGENT_RESULT_MAX_LIMIT; ")
            append("returns offset/nextOffset/totalChars/eof/truncated so you know whether more remains. ")
            append("Works for every status: partial output of running, failed, timed-out and cancelled tasks is readable with the same offset/limit. ")
            append("Reading never consumes the result — you can re-read any page at any time.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", buildJsonObject { put("type", "string") })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Start offset in characters, default 0")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Characters to return, default $SUBAGENT_RESULT_DEFAULT_LIMIT, max $SUBAGENT_RESULT_MAX_LIMIT")
                    })
                },
                required = listOf("task_id"),
            )
        },
        needsApproval = { false },
        // 会标记"已交付"，保守串行，避免同批调用争同一份交付状态。
        executionMode = ToolExecutionMode.SERIAL,
        execute = {
            val params = it.jsonObject
            val id = params.string("task_id") ?: error("task_id is required")
            val task = owned(id)
            val page = runner.readResult(id, pageOffset(params), pageLimit(params))
                ?: error("Task not found: $id")
            // 通过 result 读到也算交付，之后的 wait 只回状态 + 读取提示。
            runner.markDelivered(id)
            val text = buildJsonObject {
                page.toJsonObject().forEach { (key, value) -> put(key, value) }
                put("modelId", task.modelId?.toString() ?: "")
                put("error", task.error ?: "")
            }.toString()
            listOf(UIMessagePart.Text(text))
        },
    )

    val list = Tool(
        name = "subagent_list",
        description = "List subagent tasks started in this session with their status and result size.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = { false },
        executionMode = ToolExecutionMode.SERIAL,
        execute = {
            val items = runner.list().filter { it.conversationId == conversationId }.joinToString("\n") { t ->
                "${t.id}\t${t.status.name}\t${t.modelId}\t${runner.resultChars(t.id)}\t${t.prompt.take(60)}"
            }
            listOf(UIMessagePart.Text(items.ifBlank { "no subagent tasks" }))
        },
    )

    val cancel = Tool(
        name = "subagent_cancel",
        description = "Cancel a running subagent task. Its partial result stays readable and a cancellation notice is posted once.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", buildJsonObject { put("type", "string") })
                },
                required = listOf("task_id"),
            )
        },
        needsApproval = { false },
        executionMode = ToolExecutionMode.SERIAL,
        execute = {
            val params = it.jsonObject
            val id = params.string("task_id") ?: error("task_id is required")
            owned(id)
            val ok = runner.cancel(id)
            listOf(UIMessagePart.Text(buildJsonObject { put("ok", ok) }.toString()))
        },
    )

    return listOf(run, wait, result, list, cancel)
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.stringArray(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
