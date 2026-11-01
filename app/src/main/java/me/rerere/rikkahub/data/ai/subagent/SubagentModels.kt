package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

enum class SubagentStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT,
}

data class SubagentTask(
    val id: String,
    val prompt: String,
    val modelId: Uuid?,
    val conversationId: Uuid?,
    val status: SubagentStatus = SubagentStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val usage: TokenUsage? = null,
    val steps: Int = 0,
    val resultPath: String? = null,
    val logPath: String? = null,
) {
    val isFinished: Boolean
        get() = status in setOf(
            SubagentStatus.COMPLETED,
            SubagentStatus.FAILED,
            SubagentStatus.CANCELLED,
            SubagentStatus.TIMEOUT,
        )
}

/** subagent_result / subagent_wait 每页默认字符数。 */
const val SUBAGENT_RESULT_DEFAULT_LIMIT = 6_000

/** 单页上限：一次调用不会把整份长结果倒进上下文。 */
const val SUBAGENT_RESULT_MAX_LIMIT = 12_000

/** 一次 subagent_wait 最多同时等待的任务数。 */
const val SUBAGENT_MAX_WAIT_TASKS = 8

/** 完成通知里带的正文预览上限；通知只做指针，正文靠 subagent_result 分页读。 */
const val SUBAGENT_NOTICE_PREVIEW_CHARS = 200

/**
 * 一次结果读取的切片。
 *
 * 分页信息是给模型判断"还要不要再读"的唯一依据：
 * [nextOffset] 是下一页的起点（等于 offset + 本次返回字符数），[eof] 表示已经读到末尾。
 * [truncated] 只表示"这不是全文，后面还有"，不表示内容被丢弃——任何状态、任何 offset 都可以重新读。
 */
data class SubagentResultPage(
    val taskId: String,
    val status: SubagentStatus,
    val steps: Int,
    val offset: Int,
    val limit: Int,
    val totalChars: Int,
    val text: String,
) {
    val eof: Boolean get() = nextOffset >= totalChars

    val nextOffset: Int get() = offset + text.length

    val truncated: Boolean get() = !eof

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("taskId", taskId)
        put("status", status.name)
        put("steps", steps)
        put("offset", offset)
        put("limit", limit)
        put("totalChars", totalChars)
        put("returnedChars", text.length)
        put("nextOffset", nextOffset)
        put("eof", eof)
        put("truncated", truncated)
        put("result", text)
        if (!eof) {
            put(
                "readHint",
                "Read the rest with subagent_result(task_id=\"$taskId\", offset=$nextOffset).",
            )
        }
    }
}

/**
 * 按 offset/limit 切一段结果。
 *
 * 纯函数，便于单测：offset 越界时夹到末尾返回空页（eof=true），limit 非正数用默认值，
 * 大于上限按上限截断。
 */
fun subagentResultPage(
    task: SubagentTask,
    full: String,
    offset: Int,
    limit: Int,
): SubagentResultPage {
    val safeLimit = if (limit <= 0) SUBAGENT_RESULT_DEFAULT_LIMIT else limit.coerceAtMost(SUBAGENT_RESULT_MAX_LIMIT)
    val start = offset.coerceIn(0, full.length)
    val end = (start + safeLimit).coerceAtMost(full.length)
    return SubagentResultPage(
        taskId = task.id,
        status = task.status,
        steps = task.steps,
        offset = start,
        limit = safeLimit,
        totalChars = full.length,
        text = full.substring(start, end),
    )
}

/**
 * 一次等待里解析出的任务列表：`task_ids`（最多 [SUBAGENT_MAX_WAIT_TASKS] 个）与旧的 `task_id` 都支持。
 *
 * 去重但保序，返回顺序就是结果顺序；空输入和超量都在发请求前直接报错。
 */
fun resolveWaitTaskIds(taskIds: Collection<String>?, taskId: String?): List<String> {
    val ids = buildList {
        taskIds?.forEach { add(it) }
        if (taskId != null) add(taskId)
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    require(ids.isNotEmpty()) { "task_id or task_ids is required" }
    require(ids.size <= SUBAGENT_MAX_WAIT_TASKS) {
        "task_ids supports at most $SUBAGENT_MAX_WAIT_TASKS tasks per call"
    }
    return ids
}

/**
 * 终态通知：同一 task 只发一次。
 *
 * [key] 用作会话收件箱的 [me.rerere.rikkahub.service.QueuedNotice.key]，
 * 同一 task 的重复通知在收件箱里原地替换而不是排队。
 */
data class SubagentNoticeEvent(
    val conversationId: Uuid,
    val key: String,
    val notice: UIMessagePart.Notice,
)

fun subagentNoticeKey(taskId: String): String = "subagent:$taskId"

/** 终态才算一次交付；用于"进入终态的那一刻发一次通知"的判定。 */
fun isSubagentTerminalTransition(old: SubagentTask, new: SubagentTask): Boolean =
    !old.isFinished && new.isFinished

/**
 * 一条完成通知。只带 taskId/status/steps/结果字符数/短 preview 和读取提示,
 * 不塞正文——正文由 subagent_result 分页读, 避免同一份内容在上下文里出现两遍。
 */
fun buildSubagentNotice(task: SubagentTask, result: String): UIMessagePart.Notice {
    val seconds = (((task.finishedAt ?: System.currentTimeMillis()) - (task.startedAt ?: task.createdAt)) / 1000)
        .coerceAtLeast(0)
    val preview = result.replace(Regex("\\s+"), " ").trim()
        .let {
            if (it.length > SUBAGENT_NOTICE_PREVIEW_CHARS) {
                it.take(SUBAGENT_NOTICE_PREVIEW_CHARS) + "…"
            } else it
        }
    val detail = when {
        task.status == SubagentStatus.COMPLETED -> "finished"
        task.status == SubagentStatus.TIMEOUT -> "timed out"
        task.status == SubagentStatus.CANCELLED -> "was cancelled"
        else -> "failed"
    }
    val text = buildString {
        appendLine("<subagent_notice>")
        appendLine(
            "Subagent task `${task.id}` $detail: status ${task.status.name} after ${seconds}s, " +
                "${task.steps} step(s), ${result.length} result chars."
        )
        task.error?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine("Error: ${if (it.length > SUBAGENT_NOTICE_PREVIEW_CHARS) it.take(SUBAGENT_NOTICE_PREVIEW_CHARS) + "…" else it}")
        }
        if (result.isBlank()) {
            appendLine("It produced no readable output; partial output may still be empty.")
        } else {
            appendLine("Preview: $preview")
        }
        appendLine(
            "Read it with subagent_result(task_id=\"${task.id}\") — ${SUBAGENT_RESULT_DEFAULT_LIMIT} chars per page " +
                "by default, use offset/limit (max $SUBAGENT_RESULT_MAX_LIMIT) for longer results. " +
                "Partial output of failed, timed-out or cancelled tasks is readable the same way."
        )
        append("</subagent_notice>")
    }
    val promptPreview = task.prompt.replace(Regex("\\s+"), " ").trim().take(60)
    return UIMessagePart.Notice(
        title = "Subagent ${task.status.name}: $promptPreview",
        text = text,
    )
}

/**
 * 结果交付记录，按 task scope 共享（runner 持有一个实例，所有工具实例看到同一份）。
 *
 * "已交付"只影响 subagent_wait 的返回内容（第二次只给 status + 读取提示），
 * 不影响 subagent_result —— 结果永远可以重新分页读取，不存在"读一次就没了"。
 */
class SubagentDeliveryRegistry {
    private val delivered = ConcurrentHashMap.newKeySet<String>()

    fun markDelivered(taskId: String) {
        delivered.add(taskId)
    }

    fun isDelivered(taskId: String): Boolean = delivered.contains(taskId)

    fun forget(taskId: String) {
        delivered.remove(taskId)
    }
}
