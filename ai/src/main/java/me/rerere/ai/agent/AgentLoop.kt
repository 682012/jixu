package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.core.sum
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// 输出为空的工具会被当成没执行过，之后每一步都会再执行一次，所以换成占位文本
private const val EMPTY_TOOL_OUTPUT = "(no output)"

private class StreamChunkHandlingException(override val cause: Throwable) : RuntimeException(cause)

internal class AgentLoop(
    private val request: AgentRequest,
    private val hooks: AgentHooks,
    private val events: FlowCollector<AgentEvent>,
) {
    private val history: List<UIMessage>

    // 本轮的助手消息；新的一轮在第一次请求模型时才创建
    private var message: UIMessage?

    init {
        val last = request.messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history = request.messages.dropLast(1)
            message = last
        } else {
            history = request.messages
            message = null
        }
    }

    suspend fun run() {
        var steps = 0
        while (true) {
            val current = message
            if (current != null && current.getTools().any { !it.isExecuted }) {
                val pending = requestApprovals(current)
                if (pending.isNotEmpty()) return finish(AgentFinishReason.AwaitingApproval(pending))
                executeTools()
            } else if (current != null && steps > 0) {
                return finish(AgentFinishReason.Completed)
            }
            if (steps == request.maxSteps) return finish(AgentFinishReason.StepLimitReached)
            generateStep()
            steps++
        }
    }

    // 新的工具调用按定义判断是否需要审批，需要的标为 Pending；返回仍在等待审批的调用
    private suspend fun requestApprovals(current: UIMessage): Set<String> {
        val updated = current.copy(
            parts = current.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted &&
                    part.approvalState == ToolApprovalState.Auto &&
                    findTool(part.toolName)?.needsApproval?.invoke(part.inputAsJson()) == true
                ) {
                    part.copy(approvalState = ToolApprovalState.Pending)
                } else {
                    part
                }
            }
        )
        if (updated != current) update(updated)
        return updated.getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId }
    }

    private suspend fun generateStep() {
        appendPendingParts()
        val current = message
        val step = hooks.prepareStep(if (current == null) history else history + current)
        val params = step.params.copy(tools = request.tools)
        // 只带着追加内容创建的消息还没有模型 id
        val base = current?.let { it.copy(modelId = it.modelId ?: params.model.id) } ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = params.model.id,
        )
        // 发给模型的副本里折叠重复的工具结果，消息本身保留完整内容
        val messages = dedupeRepeatedToolOutputs(step.messages) { findTool(it)?.repeatable == true }
        val generated = if (request.stream) {
            stream(messages, params, base)
        } else {
            generate(messages, params, base)
        }
        update(hooks.finishStep(generated, newPartsFrom = base.parts.size).copy(finishedAt = now()))
    }

    // 续写普通回复时最后一个 part 不是执行完的工具或通知，这时追加会把续写变成新的一轮
    private suspend fun appendPendingParts() {
        val current = message
        if (current != null && !current.parts.lastOrNull().isModelsTurn()) return
        val parts = hooks.pendingParts()
        if (parts.isEmpty()) return
        val base = current ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        update(base.copy(parts = base.parts + parts))
    }

    private suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
        // 本步从清空的统计开始（merge 只适合同一次请求内的快照），再与之前的步骤累加。
        val stepStart = base.copy(usage = null, generationTimeMs = null)
        return try {
            withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
                // 每次重试都从本步开始时的消息重新合并，不会把重试的响应接在已经展示的半截回复后面；
                // 各次尝试共用同一个消息 id，调用方直接覆盖即可。
                val handler = StreamChunkHandler(params.model)
                var attempt = stepStart
                var firstChunk: TimeMark? = null
                request.provider.streamText(request.providerSetting, messages, params).collect { chunk ->
                    try {
                        val mark = firstChunk ?: TimeSource.Monotonic.markNow().also { firstChunk = it }
                        attempt = handler.handle(listOf(attempt), chunk).single()
                        preview(attempt.withStepStats(base, mark.elapsedNow()), newPartsFrom = base.parts.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 合并失败或下游处理失败都不是网络故障，不能重放模型请求
                        throw StreamChunkHandlingException(e)
                    }
                }
                attempt.withStepStats(base, firstChunk?.elapsedNow() ?: Duration.ZERO)
            }
        } catch (e: StreamChunkHandlingException) {
            throw e.cause
        }
    }

    private suspend fun generate(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
        var requestMark = TimeSource.Monotonic.markNow()
        val result = withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
            requestMark = TimeSource.Monotonic.markNow()
            request.provider.generateText(request.providerSetting, messages, params)
        }
        val requestTime = requestMark.elapsedNow()
        // 和流式一样只追加 part，不并进之前步骤的文本或推理里，否则 newPartsFrom 之后就不全是这一步的内容了。
        // 一次请求只有一份 usage；没有返回时保持为空，累加时沿用之前步骤的统计
        val generated = base.copy(
            parts = base.parts + result.message.parts,
            annotations = result.message.annotations.ifEmpty { base.annotations },
            modelId = params.model.id,
            usage = result.usage,
        ).finishReasoning().withStepStats(base, requestTime)
        preview(generated, newPartsFrom = base.parts.size)
        return generated
    }

    // Adjacent, explicitly independent calls overlap. Every serial call is a full ordering barrier.
    // Only the collector coroutine updates the message/emits events; workers report through a channel.
    private suspend fun executeTools() {
        val tools = checkNotNull(message).getTools()
        val pending = tools.filter { !it.isExecuted }
        // 连续多次结果相同的调用不再执行，见 ToolRepetition.kt
        val blocked = blockedRepeatCalls(tools.filter { it.isExecuted }, pending) { findTool(it)?.repeatable == true }
        var index = 0
        while (index < pending.size) {
            val first = pending[index]
            if (first.toolCallId in blocked && first.runs()) {
                updateTool(first.toolCallId) { it.copy(output = repeatBlockedOutput()) }
                index++
                continue
            }
            if (!canRunInParallel(first, blocked)) {
                executeOne(first)
                index++
                continue
            }
            val from = index
            while (index < pending.size && canRunInParallel(pending[index], blocked)) index++
            executeParallel(pending.subList(from, index))
        }
    }

    private fun UIMessagePart.Tool.runs() =
        approvalState == ToolApprovalState.Auto || approvalState == ToolApprovalState.Approved

    private fun canRunInParallel(tool: UIMessagePart.Tool, blocked: Set<String>): Boolean =
        tool.toolCallId !in blocked &&
            (tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved) &&
            findTool(tool.toolName)?.executionMode?.allowsParallelExecution() == true &&
            !(tool.startedAt != null && findTool(tool.toolName)?.executionMode != ToolExecutionMode.PARALLEL_DEDUPLICATED)

    private suspend fun executeOne(tool: UIMessagePart.Tool) {
        val runs = tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved
        val interrupted = runs && tool.startedAt != null &&
            findTool(tool.toolName)?.executionMode != ToolExecutionMode.PARALLEL_DEDUPLICATED
        if (interrupted) {
            updateTool(tool.toolCallId) {
                it.copy(output = errorOutput(
                    "Previous execution was interrupted after it started. Its effects are unknown; " +
                        "this call was NOT replayed. Inspect the target state before issuing a new call."
                ), finishedAt = Clock.System.now())
            }
            return
        }
        if (runs) updateTool(tool.toolCallId) { it.copy(startedAt = Clock.System.now(), finishedAt = null) }
        val output = execute(tool)
        updateTool(tool.toolCallId) {
            it.copy(output = output, finishedAt = if (runs) Clock.System.now() else it.finishedAt)
        }
    }

    private sealed interface ToolProgress {
        data class Started(val calls: List<UIMessagePart.Tool>, val acknowledged: CompletableDeferred<Unit>) : ToolProgress
        data class Completed(val calls: List<UIMessagePart.Tool>, val output: List<UIMessagePart>) : ToolProgress
        data class Cancelled(val error: CancellationException) : ToolProgress
    }

    private suspend fun executeParallel(calls: List<UIMessagePart.Tool>) = coroutineScope {
        val groups = LinkedHashMap<String, MutableList<UIMessagePart.Tool>>()
        calls.forEachIndexed { index, call ->
            val args = if (findTool(call.toolName)?.executionMode == ToolExecutionMode.PARALLEL_DEDUPLICATED) {
                canonicalToolArguments(call.input)
            } else null
            val key = if (args == null) "unique:$index" else "${call.toolName}\n$args"
            groups.getOrPut(key) { mutableListOf() }.add(call)
        }
        val progress = Channel<ToolProgress>(Channel.UNLIMITED)
        val permits = Semaphore(8)
        groups.values.forEach { group ->
            launch {
                permits.withPermit {
                    val acknowledged = CompletableDeferred<Unit>()
                    progress.send(ToolProgress.Started(group, acknowledged))
                    acknowledged.await()
                    try {
                        progress.send(ToolProgress.Completed(group, execute(group.first())))
                    } catch (e: CancellationException) {
                        // A tool may cancel itself without cancelling its siblings; wake the collector too.
                        progress.trySend(ToolProgress.Cancelled(e))
                        throw e
                    }
                }
            }
        }
        repeat(groups.size * 2) {
            when (val event = progress.receive()) {
                is ToolProgress.Started -> {
                    event.calls.forEach { call ->
                        updateTool(call.toolCallId) { it.copy(startedAt = Clock.System.now(), finishedAt = null) }
                    }
                    event.acknowledged.complete(Unit)
                }
                is ToolProgress.Completed -> event.calls.forEach { call ->
                    updateTool(call.toolCallId) { it.copy(output = event.output, finishedAt = Clock.System.now()) }
                }
                is ToolProgress.Cancelled -> throw event.error
            }
        }
        progress.close()
    }

    private suspend fun updateTool(toolCallId: String, transform: (UIMessagePart.Tool) -> UIMessagePart.Tool) {
        val current = checkNotNull(message)
        update(
            current.copy(
                parts = current.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) transform(part) else part
                }
            )
        )
    }

    private suspend fun execute(tool: UIMessagePart.Tool): List<UIMessagePart> =
        when (val state = tool.approvalState) {
            is ToolApprovalState.Denied -> errorOutput(
                "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}"
            )

            is ToolApprovalState.Answered -> listOf(UIMessagePart.Text(state.answer))
            ToolApprovalState.Auto, ToolApprovalState.Approved -> runTool(tool)
            ToolApprovalState.Pending -> error("Tool ${tool.toolCallId} is still waiting for approval")
        }

    private suspend fun runTool(tool: UIMessagePart.Tool): List<UIMessagePart> = try {
        val definition = findTool(tool.toolName) ?: error("Tool ${tool.toolName} not found")
        val args = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
            .getOrElse { error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}") }
        definition.execute(args).ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }
    } catch (e: CancellationException) {
        // 取消必须向上传播，否则停止生成会被误报为工具执行错误
        throw e
    } catch (e: Throwable) {
        errorOutput("[${e.javaClass.name}] ${(e.message ?: "Tool execution failed").take(2_000)}")
    }

    private fun findTool(name: String) = request.tools.find { it.name == name }

    private suspend fun update(updated: UIMessage) {
        message = updated
        events.emit(AgentEvent.MessageUpdated(updated))
    }

    // 生成中途的内容只发给调用方，这一步结束前循环内部一直基于原始内容合并
    private suspend fun preview(generating: UIMessage, newPartsFrom: Int) {
        events.emit(AgentEvent.MessageUpdated(hooks.previewStep(generating, newPartsFrom)))
    }

    private suspend fun finish(reason: AgentFinishReason) {
        events.emit(AgentEvent.Finished(checkNotNull(message), reason))
    }
}

private fun UIMessagePart?.isModelsTurn() =
    (this is UIMessagePart.Tool && isExecuted) || this is UIMessagePart.Notice

private fun errorOutput(error: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))

// 把本步的 usage 和生成耗时累加到之前步骤的统计上；接收者只带本步的 usage
private fun UIMessage.withStepStats(stepBase: UIMessage, stepTime: Duration): UIMessage = copy(
    usage = stepBase.usage.sum(usage),
    generationTimeMs = (stepBase.generationTimeMs ?: 0L) + stepTime.inWholeMilliseconds,
    // 上下文占用只看最后一次请求，usage 是多步累计值；本步没返回 usage 时沿用之前步骤的
    lastUsage = usage ?: stepBase.lastUsage,
    stepCount = (stepBase.stepCount ?: 0) + 1,
)

private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
