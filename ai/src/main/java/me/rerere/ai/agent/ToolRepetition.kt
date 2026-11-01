package me.rerere.ai.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 跨步骤的重复工具调用处理。只看工具名、规范化后的参数和文本输出，不按工具名猜语义。
 *
 * 两件事：
 * - [dedupeRepeatedToolOutputs]：发给模型的请求里，和更早一次调用（同名、同参数）结果完全相同的输出
 *   换成一行指向那次调用的说明，消息本身与界面仍保留完整结果。只依赖更早的内容，同一段历史每次得到
 *   同样的结果，不破坏提示词缓存的前缀。
 * - [blockedRepeatCalls]：同名同参数的调用已经连续执行 [MAX_IDENTICAL_REPEATS] 次且结果都一样时，
 *   下一次不再执行，直接回错误让模型换路，参照 Gemini CLI 的 TOOL_CALL_LOOP_THRESHOLD = 5。
 *
 * 轮询类工具（等待子代理、查后台任务状态）结果相同是正常的，这类工具用 [me.rerere.ai.core.Tool.repeatable] 豁免两者。
 */

/** 连续执行几次相同的调用后拦下下一次 */
internal const val MAX_IDENTICAL_REPEATS = 4

/** 比这短的输出不去重：省下的 token 不值得多一行说明 */
internal const val DEDUPE_MIN_OUTPUT_CHARS = 400

/** 请求里第几次出现同一结果起提示换路 */
private const val REPEAT_HINT_FROM = 3

internal const val REPEAT_BLOCKED_MESSAGE =
    "Not executed: this exact call already ran $MAX_IDENTICAL_REPEATS times in a row with identical results. " +
        "Use the earlier result, change the arguments or approach, or stop and report what is blocking you."

private val REPEAT_BLOCKED_TEXT = buildJsonObject { put("error", REPEAT_BLOCKED_MESSAGE) }.toString()

/** 被拦下的调用的输出，格式与其他工具错误一致 */
internal fun repeatBlockedOutput(): List<UIMessagePart> = listOf(UIMessagePart.Text(REPEAT_BLOCKED_TEXT))

private data class ToolCallKey(val name: String, val arguments: String)

private fun UIMessagePart.Tool.callKey(): ToolCallKey? =
    canonicalToolArguments(input)?.let { ToolCallKey(toolName, it) }

/** 只有纯文本的输出才参与比较；带图片、文件等的输出原样保留 */
private fun UIMessagePart.Tool.textOutput(): String? =
    if (output.isNotEmpty() && output.all { it is UIMessagePart.Text }) {
        output.joinToString("") { (it as UIMessagePart.Text).text }
    } else {
        null
    }

internal fun dedupeRepeatedToolOutputs(
    messages: List<UIMessage>,
    repeatable: (String) -> Boolean,
): List<UIMessage> {
    data class Seen(val toolCallId: String, var count: Int)

    val seen = HashMap<Pair<ToolCallKey, String>, Seen>()
    var changed = false
    val result = messages.map { message ->
        var messageChanged = false
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || !part.isExecuted || repeatable(part.toolName)) return@map part
            val key = part.callKey() ?: return@map part
            val text = part.textOutput() ?: return@map part
            if (text.length < DEDUPE_MIN_OUTPUT_CHARS) return@map part
            val earlier = seen[key to text]
            if (earlier == null) {
                seen[key to text] = Seen(part.toolCallId, 1)
                return@map part
            }
            earlier.count++
            messageChanged = true
            val hint = if (earlier.count >= REPEAT_HINT_FROM) {
                " This call has now returned the same result ${earlier.count} times; use that result or change approach."
            } else {
                ""
            }
            part.copy(
                output = listOf(
                    UIMessagePart.Text(
                        "[Same result as earlier call ${earlier.toolCallId} with identical arguments " +
                            "(${text.length} chars), not repeated here.$hint]"
                    )
                )
            )
        }
        if (messageChanged) {
            changed = true
            message.copy(parts = parts)
        } else {
            message
        }
    }
    return if (changed) result else messages
}

/**
 * [executed] 是本轮已经执行过的工具调用（按顺序），返回 [pending] 中应拦下的 toolCallId。
 * 只数紧挨着的一串相同调用：中间夹了别的调用（可能改了状态）就重新计数。
 * 被拦下的调用不打断计数，模型坚持重发会一直被拦，直到它换一个调用。
 */
internal fun blockedRepeatCalls(
    executed: List<UIMessagePart.Tool>,
    pending: List<UIMessagePart.Tool>,
    repeatable: (String) -> Boolean,
): Set<String> {
    val history = executed.filter { it.isExecuted && it.textOutput() != REPEAT_BLOCKED_TEXT }
    if (history.size < MAX_IDENTICAL_REPEATS) return emptySet()
    val tail = history.takeLast(MAX_IDENTICAL_REPEATS)
    val key = tail.first().callKey() ?: return emptySet()
    val text = tail.first().textOutput() ?: return emptySet()
    if (repeatable(tail.first().toolName)) return emptySet()
    if (!tail.all { it.callKey() == key && it.textOutput() == text }) return emptySet()
    return pending.filter { it.callKey() == key }.mapTo(LinkedHashSet()) { it.toolCallId }
}
