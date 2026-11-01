package me.rerere.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * 独立验证工具调度：相邻并发窗口、串行屏障、窗口内去重、取消与中断恢复。
 *
 * 只通过 [runAgent] 的公开入口观察行为，不断言实现细节；时间用 runTest 的虚拟时钟度量，
 * 因此并行/串行的耗时差异是确定性的，不依赖真实时钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentToolSchedulingTest {
    private val model = Model(modelId = "scheduling-model")
    private val provider = SchedulingFakeProvider()
    private val hooks = SchedulingHooks(model)

    private fun agentRequest(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 16,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        // 非流式：工具调用与参数由预设结果直接给出，便于精确控制 canonical JSON 的写法
        stream = false,
        maxSteps = maxSteps,
    )

    private suspend fun collectEvents(request: AgentRequest = agentRequest()) =
        runAgent(request, hooks).toList()

    // ---------------------------------------------------------------- 并发窗口

    @Test
    fun `four independent reads cost one delay instead of four`() = runTest {
        val delayMs = 500L
        val runs = AtomicInteger()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL) { args ->
            runs.incrementAndGet()
            delay(delayMs)
            listOf(UIMessagePart.Text("data:" + argText(args, "path")))
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "read", """{"path":"a"}"""),
                toolCall("call-2", "read", """{"path":"b"}"""),
                toolCall("call-3", "read", """{"path":"c"}"""),
                toolCall("call-4", "read", """{"path":"d"}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val startedAt = testScheduler.currentTime
        val finished = collectEvents(agentRequest(tools = listOf(read))).finished()
        val elapsed = testScheduler.currentTime - startedAt

        assertEquals(4, runs.get())
        assertEquals(AgentFinishReason.Completed, finished.reason)
        // 每个调用都独立执行，结果不能互相串
        assertEquals("data:a", finished.message.toolPart("call-1").outputText())
        assertEquals("data:b", finished.message.toolPart("call-2").outputText())
        assertEquals("data:c", finished.message.toolPart("call-3").outputText())
        assertEquals("data:d", finished.message.toolPart("call-4").outputText())
        assertTrue("耗时应至少是一倍延迟，实际 $elapsed", elapsed >= delayMs)
        assertTrue("四个并发读应约等于一倍延迟而不是四倍，实际 $elapsed", elapsed < delayMs * 2)
    }

    @Test
    fun `one parallel window keeps at most eight tools active`() = runTest {
        val runs = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL) {
            runs.incrementAndGet()
            val now = active.incrementAndGet()
            if (now > peak.get()) peak.set(now)
            try {
                delay(100)
            } finally {
                active.decrementAndGet()
            }
            okPart()
        }
        val calls: List<UIMessagePart> = (1..9).map { toolCall("call-$it", "read", """{"i":$it}""") }
        provider.result(assistantMessage(*calls.toTypedArray()))
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(read))).finished()

        assertEquals(9, runs.get())
        assertEquals("同一窗口最多 8 个并发", 8, peak.get())
        assertEquals(9, finished.message.getTools().size)
        assertTrue(finished.message.getTools().all { it.isExecuted })
    }

    @Test
    fun `a serial write between parallel reads is a full ordering barrier`() = runTest {
        val log = mutableListOf<String>()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL) { args ->
            val id = argText(args, "id")
            log += "read$id-start"
            delay(100)
            log += "read$id-end"
            listOf(UIMessagePart.Text("read$id"))
        }
        val write = schedulingTool("write", ToolExecutionMode.SERIAL) { args ->
            val id = argText(args, "id")
            log += "write$id-start"
            delay(100)
            log += "write$id-end"
            listOf(UIMessagePart.Text("write$id"))
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "read", """{"id":"1"}"""),
                toolCall("call-2", "write", """{"id":"2"}"""),
                toolCall("call-3", "read", """{"id":"3"}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(read, write))).finished()

        // 写是完整屏障：它不能和任何一侧的读重叠，读也不能跨过它
        assertEquals(
            listOf("read1-start", "read1-end", "write2-start", "write2-end", "read3-start", "read3-end"),
            log,
        )
        assertEquals("read1", finished.message.toolPart("call-1").outputText())
        assertEquals("write2", finished.message.toolPart("call-2").outputText())
        assertEquals("read3", finished.message.toolPart("call-3").outputText())
    }

    @Test
    fun `tools without an explicit execution mode are ordered barriers`() = runTest {
        val log = mutableListOf<String>()
        val first = Tool(
            name = "first",
            description = "first",
            execute = {
                log += "first-start"
                delay(100)
                log += "first-end"
                okPart()
            },
        )
        val second = Tool(
            name = "second",
            description = "second",
            execute = {
                log += "second-start"
                delay(100)
                log += "second-end"
                okPart()
            },
        )
        provider.result(
            assistantMessage(
                toolCall("call-1", "first", """{"a":1}"""),
                toolCall("call-2", "second", """{"a":1}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(first, second))).finished()

        // 默认就是 SERIAL：即使参数一样也不并发、不合并
        assertEquals(listOf("first-start", "first-end", "second-start", "second-end"), log)
        assertTrue(finished.message.toolPart("call-1").isExecuted)
        assertTrue(finished.message.toolPart("call-2").isExecuted)
    }

    // ---------------------------------------------------------------- 窗口内去重

    @Test
    fun `deduplicated calls with reordered arguments share one execution`() = runTest {
        val runs = AtomicInteger()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            runs.incrementAndGet()
            delay(50)
            listOf(UIMessagePart.Text("payload"))
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "read", """{"path":"a","depth":2}"""),
                toolCall("call-2", "read", """{"depth":2,"path":"a"}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(read))).finished()
        val first = finished.message.toolPart("call-1")
        val second = finished.message.toolPart("call-2")

        assertEquals("键顺序不同但参数相同，只应执行一次", 1, runs.get())
        // 两个 toolCallId 都必须拿到自己的结果，否则其中一个会被当成没执行
        assertTrue(first.isExecuted)
        assertTrue(second.isExecuted)
        assertEquals("payload", first.outputText())
        assertEquals("payload", second.outputText())
        assertNotNull(first.startedAt)
        assertNotNull(second.startedAt)
        assertNotNull(first.finishedAt)
        assertNotNull(second.finishedAt)
    }

    @Test
    fun `deduplicated calls with different arguments both execute`() = runTest {
        val runs = AtomicInteger()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) { args ->
            runs.incrementAndGet()
            listOf(UIMessagePart.Text("data:" + argText(args, "path")))
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "read", """{"path":"a"}"""),
                toolCall("call-2", "read", """{"path":"b"}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(read))).finished()

        assertEquals(2, runs.get())
        assertEquals("data:a", finished.message.toolPart("call-1").outputText())
        assertEquals("data:b", finished.message.toolPart("call-2").outputText())
    }

    @Test
    fun `parallel calls with identical arguments still execute twice`() = runTest {
        val runs = AtomicInteger()
        val write = schedulingTool("touch", ToolExecutionMode.PARALLEL) {
            val order = runs.incrementAndGet()
            listOf(UIMessagePart.Text("run$order"))
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "touch", """{"target":"x"}"""),
                toolCall("call-2", "touch", """{"target":"x"}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(write))).finished()

        assertEquals("非 DEDUP 的并发调用不能被合并", 2, runs.get())
        assertTrue(finished.message.toolPart("call-1").isExecuted)
        assertTrue(finished.message.toolPart("call-2").isExecuted)
    }

    @Test
    fun `the same read runs again on a later model step`() = runTest {
        val runs = AtomicInteger()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            runs.incrementAndGet()
            listOf(UIMessagePart.Text("data"))
        }
        provider.result(assistantMessage(toolCall("call-1", "read", """{"path":"a"}""")))
        provider.result(assistantMessage(toolCall("call-2", "read", """{"path":"a"}""")))
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(agentRequest(tools = listOf(read))).finished()

        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(3, provider.requests.size)
        // 去重只在同一相邻窗口内生效，不跨模型 step，也没有缓存
        assertEquals(2, runs.get())
        assertTrue(finished.message.toolPart("call-1").isExecuted)
        assertTrue(finished.message.toolPart("call-2").isExecuted)
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun `cancelling keeps finished results and never marks calls that did not start`() = runTest {
        val secondStarted = CompletableDeferred<Unit>()
        val thirdRuns = AtomicInteger()
        val first = schedulingTool("first", ToolExecutionMode.SERIAL) {
            listOf(UIMessagePart.Text("first done"))
        }
        val second = schedulingTool("second", ToolExecutionMode.SERIAL) {
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        val third = schedulingTool("third", ToolExecutionMode.SERIAL) {
            thirdRuns.incrementAndGet()
            okPart()
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "first"),
                toolCall("call-2", "second"),
                toolCall("call-3", "third"),
            )
        )

        val events = mutableListOf<AgentEvent>()
        val job = launch {
            runAgent(agentRequest(tools = listOf(first, second, third)), hooks).toList(events)
        }
        secondStarted.await()
        job.cancelAndJoin()

        val last = (events.last() as AgentEvent.MessageUpdated).message
        // 已经跑完的结果不能因为取消丢掉
        assertEquals("first done", last.toolPart("call-1").outputText())
        assertNotNull(last.toolPart("call-1").startedAt)
        assertNotNull(last.toolPart("call-1").finishedAt)
        // 正在跑的那个保留了 startedAt，界面还能显示它被中断了
        assertNotNull(last.toolPart("call-2").startedAt)
        assertNull(last.toolPart("call-2").finishedAt)
        assertFalse(last.toolPart("call-2").isExecuted)
        // 串行屏障之后、从未启动的调用不能被写上 startedAt
        assertEquals(0, thirdRuns.get())
        assertNull(last.toolPart("call-3").startedAt)
        assertNull(last.toolPart("call-3").finishedAt)
        assertFalse(last.toolPart("call-3").isExecuted)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `a tool that cancels itself cancels the whole run without deadlocking`() = runTest {
        val hanging = schedulingTool("hang", ToolExecutionMode.PARALLEL) { awaitCancellation() }
        val suicidal = schedulingTool("self-cancel", ToolExecutionMode.PARALLEL) {
            delay(10)
            currentCoroutineContext().cancel()
            awaitCancellation()
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "hang"),
                toolCall("call-2", "self-cancel"),
            )
        )
        // 若取消被当成普通结束，第二步会请求模型；这里给一份结果让失败信息更直接
        provider.result(UIMessage.assistant("unexpected"))

        var thrown: Throwable? = null
        var returnedNormally = false
        var hung = false
        val job = launch {
            try {
                withTimeout(10_000) {
                    try {
                        runAgent(agentRequest(tools = listOf(hanging, suicidal)), hooks).toList()
                        returnedNormally = true
                    } catch (e: Throwable) {
                        thrown = e
                    }
                }
            } catch (e: TimeoutCancellationException) {
                hung = true
            }
        }
        job.join()

        // 兄弟工具一直在等，如果取消没有整体传播，这里会一直挂到超时
        assertFalse("工具自取消后整个运行应当结束，不能 deadlock", hung)
        assertFalse("工具自取消不能被当成正常结束", returnedNormally)
        assertTrue(
            "整体应当以 CancellationException 结束，实际 $thrown",
            thrown is CancellationException && thrown !is TimeoutCancellationException,
        )
    }

    // ---------------------------------------------------------------- 审批

    @Test
    fun `one call needing approval holds the whole batch`() = runTest {
        val executed = mutableListOf<String>()
        val write = schedulingTool("write", ToolExecutionMode.PARALLEL, needsApproval = true) {
            executed += "write"
            okPart()
        }
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL) {
            executed += "read"
            okPart()
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "write"),
                toolCall("call-2", "read"),
            )
        )

        val finished = collectEvents(agentRequest(tools = listOf(write, read))).finished()

        assertEquals(AgentFinishReason.AwaitingApproval(setOf("call-1")), finished.reason)
        assertEquals(ToolApprovalState.Pending, finished.message.toolPart("call-1").approvalState)
        // 同批的其它调用停在原地，不能先跑掉
        assertEquals(ToolApprovalState.Auto, finished.message.toolPart("call-2").approvalState)
        assertTrue(executed.isEmpty())
        assertEquals(1, provider.requests.size)
        assertNull(finished.message.toolPart("call-2").startedAt)
    }

    @Test
    fun `denied and answered calls are not executed but keep their outputs`() = runTest {
        val executed = mutableListOf<String>()
        val ask = schedulingTool("ask", ToolExecutionMode.PARALLEL) {
            executed += "ask"
            okPart()
        }
        val danger = schedulingTool("danger", ToolExecutionMode.PARALLEL) {
            executed += "danger"
            okPart()
        }
        val message = assistantMessage(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "ask",
                input = "{}",
                approvalState = ToolApprovalState.Answered("blue"),
            ),
            UIMessagePart.Tool(
                toolCallId = "call-2",
                toolName = "danger",
                input = "{}",
                approvalState = ToolApprovalState.Denied("too risky"),
            ),
        )
        provider.result(UIMessage.assistant("ok"))

        val finished = collectEvents(
            agentRequest(messages = listOf(UIMessage.user("hi"), message), tools = listOf(ask, danger))
        ).finished()

        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertTrue(executed.isEmpty())
        assertEquals("blue", finished.message.toolPart("call-1").outputText())
        assertTrue(finished.message.toolPart("call-2").outputText().contains("too risky"))
        // 没有真正运行的工具不计时
        assertNull(finished.message.toolPart("call-1").startedAt)
        assertNull(finished.message.toolPart("call-2").startedAt)
        assertEquals(1, provider.requests.size)
    }

    // ---------------------------------------------------------------- 中断恢复

    @Test
    fun `a started write without output is not replayed and the model continues`() = runTest {
        val runs = AtomicInteger()
        val write = schedulingTool("write", ToolExecutionMode.SERIAL) {
            runs.incrementAndGet()
            okPart()
        }
        val saved = assistantMessage(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "write",
                input = """{"target":"file.txt"}""",
                startedAt = Clock.System.now(),
            )
        )
        provider.result(UIMessage.assistant("checked"))

        val finished = collectEvents(
            agentRequest(messages = listOf(UIMessage.user("hi"), saved), tools = listOf(write))
        ).finished()

        // 有 startedAt 却没有 output 的写=上一次执行被中断，效果未知，不能自动重放
        assertEquals(0, runs.get())
        val output = finished.message.toolPart("call-1").outputText()
        assertTrue("提示里要说明被中断：$output", output.contains("interrupted"))
        assertTrue("提示里要说明没有重放：$output", output.contains("NOT replayed"))
        assertTrue("提示里要让模型核对目标状态：$output", output.contains("Inspect"))
        // 不阻塞：模型仍然被继续请求，并且能看到这条结果
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(1, provider.requests.size)
        val sentBack = provider.requests.single().messages.last().toolPart("call-1")
        assertTrue(sentBack.isExecuted)
        assertEquals(output, sentBack.outputText())
    }

    @Test
    fun `a deduplicated read that started without output is replayed`() = runTest {
        val runs = AtomicInteger()
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            runs.incrementAndGet()
            listOf(UIMessagePart.Text("fresh"))
        }
        val saved = assistantMessage(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "read",
                input = """{"path":"a"}""",
                startedAt = Clock.System.now(),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val finished = collectEvents(
            agentRequest(messages = listOf(UIMessage.user("hi"), saved), tools = listOf(read))
        ).finished()

        // 只读且显式声明可去重的调用重放是安全的
        assertEquals(1, runs.get())
        assertEquals("fresh", finished.message.toolPart("call-1").outputText())
        assertEquals(AgentFinishReason.Completed, finished.reason)
    }

    // ---------------------------------------------------------------- 输出一致性

    @Test
    fun `parallel windows only emit forward moving snapshots`() = runTest {
        val read = schedulingTool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            delay(50)
            listOf(UIMessagePart.Text("payload"))
        }
        provider.result(
            assistantMessage(
                toolCall("call-1", "read", """{"p":1}"""),
                toolCall("call-2", "read", """{"p":2}"""),
                toolCall("call-3", "read", """{"p":3}"""),
            )
        )
        provider.result(UIMessage.assistant("done"))

        val events = collectEvents(agentRequest(tools = listOf(read)))
        val snapshots = events.filterIsInstance<AgentEvent.MessageUpdated>().map { it.message }
        assertTrue("至少要有每个工具的启动和完成快照", snapshots.size >= 4)

        val seen = mutableMapOf<String, UIMessagePart.Tool>()
        snapshots.forEach { message ->
            message.getTools().forEach { tool ->
                val previous = seen[tool.toolCallId]
                if (previous != null) {
                    assertTrue("${tool.toolCallId} 的执行结果回退了", !previous.isExecuted || tool.isExecuted)
                    assertTrue(
                        "${tool.toolCallId} 的 startedAt 被抹掉了",
                        previous.startedAt == null || tool.startedAt != null,
                    )
                    if (previous.isExecuted) {
                        assertEquals(
                            "${tool.toolCallId} 的结果被覆盖",
                            previous.outputText(),
                            tool.outputText(),
                        )
                    }
                }
                seen[tool.toolCallId] = tool
            }
        }

        val finished = events.finished()
        assertTrue(
            "每个 toolCallId 都要有自己的结果",
            finished.message.getTools().map { it.toolCallId }.toSet() == setOf("call-1", "call-2", "call-3"),
        )
        assertTrue(finished.message.getTools().all { it.isExecuted })
    }
}

/** 按调用顺序返回预设的非流式结果，并记录每次请求 */
private class SchedulingFakeProvider : Provider<ProviderSetting> {
    data class Request(val messages: List<UIMessage>, val params: TextGenerationParams)

    val requests = mutableListOf<Request>()
    private val results = ArrayDeque<TextGenerationResult>()

    fun result(message: UIMessage) {
        results += TextGenerationResult(id = "scheduling-result", model = "scheduling-model", message = message)
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        requests += Request(messages, params)
        return results.removeFirst()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = error("这些用例只用非流式结果")
}

private class SchedulingHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>) =
        StepRequest(messages, TextGenerationParams(model = model))
}

private fun schedulingTool(
    name: String,
    executionMode: ToolExecutionMode,
    needsApproval: Boolean = false,
    execute: suspend (JsonElement) -> List<UIMessagePart>,
) = Tool(
    name = name,
    description = name,
    needsApproval = { needsApproval },
    executionMode = executionMode,
    execute = execute,
)

private fun toolCall(id: String, name: String, input: String = "{}") = UIMessagePart.Tool(
    toolCallId = id,
    toolName = name,
    input = input,
)

private fun assistantMessage(vararg parts: UIMessagePart) =
    UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

private fun okPart(): List<UIMessagePart> = listOf(UIMessagePart.Text("ok"))

private fun argText(args: JsonElement, key: String): String =
    args.jsonObject[key]?.jsonPrimitive?.content.orEmpty()

private fun UIMessage.toolPart(id: String) = getTools().single { it.toolCallId == id }

private fun UIMessagePart.Tool.outputText() =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

private fun List<AgentEvent>.finished() = last() as AgentEvent.Finished
