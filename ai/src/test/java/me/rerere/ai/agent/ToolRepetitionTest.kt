package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 跨步骤重复调用：请求里折叠相同结果、连续相同调用熔断、轮询工具豁免 */
class ToolRepetitionTest {
    private val model = Model(modelId = "repetition-model")
    private val provider = RepetitionFakeProvider()
    private val hooks = RepetitionHooks(model)
    private val longText = "x".repeat(DEDUPE_MIN_OUTPUT_CHARS + 100)

    private suspend fun run(tools: List<Tool>) = runAgent(
        AgentRequest(
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(),
            messages = listOf(UIMessage.user("hi")),
            tools = tools,
            stream = false,
            maxSteps = 16,
        ),
        hooks,
    ).toList()

    private fun stepCalling(vararg calls: Pair<String, String>) {
        provider.result(assistant(*calls.map { (id, input) -> call(id, "read", input) }.toTypedArray()))
    }

    // ---------------------------------------------------------------- 请求里折叠重复结果

    @Test
    fun `identical result is folded in the request but kept in the message`() = runTest {
        val read = repetitionTool("read") { listOf(UIMessagePart.Text(longText)) }
        stepCalling("call-1" to """{"path":"a"}""")
        stepCalling("call-2" to """{"path":"a"}""")
        provider.result(UIMessage.assistant("done"))

        val finished = run(listOf(read)).last() as AgentEvent.Finished

        val sent = provider.requests.last().flatMap { it.getTools() }
        assertEquals(longText, sent.single { it.toolCallId == "call-1" }.outputText())
        val folded = sent.single { it.toolCallId == "call-2" }.outputText()
        assertTrue(folded, folded.startsWith("[Same result as earlier call call-1"))
        // 消息与界面保留完整结果
        assertEquals(longText, finished.message.getTools().single { it.toolCallId == "call-2" }.outputText())
    }

    @Test
    fun `different arguments, short outputs and repeatable tools are never folded`() = runTest {
        val read = repetitionTool("read") { listOf(UIMessagePart.Text(longText)) }
        val short = repetitionTool("short") { listOf(UIMessagePart.Text("same")) }
        val poll = repetitionTool("poll", repeatable = true) { listOf(UIMessagePart.Text(longText)) }
        provider.result(assistant(call("c1", "read", """{"path":"a"}"""), call("c2", "short"), call("c3", "poll")))
        provider.result(assistant(call("c4", "read", """{"path":"b"}"""), call("c5", "short"), call("c6", "poll")))
        provider.result(UIMessage.assistant("done"))

        run(listOf(read, short, poll))

        val sent = provider.requests.last().flatMap { it.getTools() }.associate { it.toolCallId to it.outputText() }
        assertEquals(longText, sent["c4"])
        assertEquals("same", sent["c5"])
        assertEquals(longText, sent["c6"])
    }

    @Test
    fun `argument key order does not matter`() {
        val messages = listOf(
            assistant(
                executed("c1", """{"a":1,"b":2}""", longText),
                executed("c2", """{"b":2,"a":1}""", longText),
            )
        )
        val folded = dedupeRepeatedToolOutputs(messages) { false }.single().getTools()
        assertEquals(longText, folded[0].outputText())
        assertTrue(folded[1].outputText().startsWith("[Same result as earlier call c1"))
    }

    @Test
    fun `nothing to fold returns the same list and non text outputs are kept`() {
        val image = UIMessagePart.Tool(
            toolCallId = "img-2",
            toolName = "read",
            input = """{"path":"a.png"}""",
            output = listOf(UIMessagePart.Image(url = "file:///a.png")),
        )
        val messages = listOf(
            assistant(executed("c1", """{"path":"a"}""", longText), image.copy(toolCallId = "img-1"), image)
        )
        assertSame(messages, dedupeRepeatedToolOutputs(messages) { false })
    }

    @Test
    fun `third identical result carries a hint to change approach`() {
        val messages = listOf(
            assistant(
                executed("c1", """{"p":1}""", longText),
                executed("c2", """{"p":1}""", longText),
                executed("c3", """{"p":1}""", longText),
            )
        )
        val tools = dedupeRepeatedToolOutputs(messages) { false }.single().getTools()
        assertFalse(tools[1].outputText().contains("change approach"))
        assertTrue(tools[2].outputText().contains("3 times"))
    }

    // ---------------------------------------------------------------- 连续相同调用熔断

    @Test
    fun `fifth identical call in a row is not executed`() = runTest {
        val runs = AtomicInteger()
        val read = repetitionTool("read") { runs.incrementAndGet(); listOf(UIMessagePart.Text("same")) }
        repeat(MAX_IDENTICAL_REPEATS + 1) { stepCalling("call-$it" to """{"path":"a"}""") }
        provider.result(UIMessage.assistant("done"))

        val finished = run(listOf(read)).last() as AgentEvent.Finished

        assertEquals(MAX_IDENTICAL_REPEATS, runs.get())
        val last = finished.message.getTools().last()
        assertTrue(last.outputText(), last.outputText().contains("Not executed"))
        assertEquals(AgentFinishReason.Completed, finished.reason)
    }

    @Test
    fun `blocked call keeps blocking while the model insists`() = runTest {
        val runs = AtomicInteger()
        val read = repetitionTool("read") { runs.incrementAndGet(); listOf(UIMessagePart.Text("same")) }
        repeat(MAX_IDENTICAL_REPEATS + 2) { stepCalling("call-$it" to """{"path":"a"}""") }
        provider.result(UIMessage.assistant("done"))

        val tools = (run(listOf(read)).last() as AgentEvent.Finished).message.getTools()

        assertEquals(MAX_IDENTICAL_REPEATS, runs.get())
        assertTrue(tools.takeLast(2).all { it.outputText().contains("Not executed") })
    }

    @Test
    fun `a different call in between resets the count`() = runTest {
        val runs = AtomicInteger()
        val read = repetitionTool("read") { args ->
            runs.incrementAndGet()
            listOf(UIMessagePart.Text("data:" + args.jsonObject["path"]?.jsonPrimitive?.content))
        }
        repeat(MAX_IDENTICAL_REPEATS - 1) { stepCalling("a-$it" to """{"path":"a"}""") }
        stepCalling("b" to """{"path":"b"}""")
        repeat(MAX_IDENTICAL_REPEATS - 1) { stepCalling("c-$it" to """{"path":"a"}""") }
        provider.result(UIMessage.assistant("done"))

        run(listOf(read))

        assertEquals(2 * (MAX_IDENTICAL_REPEATS - 1) + 1, runs.get())
    }

    @Test
    fun `changed output is not a loop`() = runTest {
        val runs = AtomicInteger()
        val read = repetitionTool("read") { listOf(UIMessagePart.Text("run ${runs.incrementAndGet()}")) }
        repeat(MAX_IDENTICAL_REPEATS + 2) { stepCalling("call-$it" to """{"path":"a"}""") }
        provider.result(UIMessage.assistant("done"))

        run(listOf(read))

        assertEquals(MAX_IDENTICAL_REPEATS + 2, runs.get())
    }

    @Test
    fun `repeatable tools are never blocked`() = runTest {
        val runs = AtomicInteger()
        val poll = repetitionTool("read", repeatable = true) { runs.incrementAndGet(); listOf(UIMessagePart.Text("running")) }
        repeat(MAX_IDENTICAL_REPEATS + 2) { stepCalling("call-$it" to """{"job":"1"}""") }
        provider.result(UIMessage.assistant("done"))

        run(listOf(poll))

        assertEquals(MAX_IDENTICAL_REPEATS + 2, runs.get())
    }
}

private class RepetitionFakeProvider : Provider<ProviderSetting> {
    val requests = mutableListOf<List<UIMessage>>()
    private val results = ArrayDeque<TextGenerationResult>()

    fun result(message: UIMessage) {
        results.addLast(TextGenerationResult(id = "repetition-result", model = "repetition-model", message = message))
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        requests += messages
        return results.removeFirst()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = error("这些用例只用非流式结果")
}

private class RepetitionHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>) =
        StepRequest(messages, TextGenerationParams(model = model))
}

private fun repetitionTool(
    name: String,
    repeatable: Boolean = false,
    execute: suspend (JsonElement) -> List<UIMessagePart>,
) = Tool(
    name = name,
    description = name,
    executionMode = ToolExecutionMode.SERIAL,
    repeatable = repeatable,
    execute = execute,
)

private fun call(id: String, name: String, input: String = "{}") =
    UIMessagePart.Tool(toolCallId = id, toolName = name, input = input)

private fun executed(id: String, input: String, output: String) =
    call(id, "read", input).copy(output = listOf(UIMessagePart.Text(output)))

private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

private fun UIMessagePart.Tool.outputText() = output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
