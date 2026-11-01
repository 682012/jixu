package me.rerere.ai.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamChunkHandler] 的一个实例只代表一次响应（多步工具调用里的一步），而它拿到的助手消息
 * 可能已经带着之前步骤生成的、已经执行过的工具 part。
 *
 * 这些用例锁定 id 隔离的边界：Provider 复用同一个工具调用 id 时，新调用必须落到新 part 上，
 * 不能污染旧 part 的入参/结果，也不能把新调用吞掉；同一步内的重复事件与非重复的真实 id
 * 行为保持不变。
 */
class StreamChunkHandlerToolCallIdTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `reused provider id in the next step must not swallow the new call`() {
        // 第一步：provider 没给 id，decoder 合成 response:tool-0，执行完成后结果是旧调用
        var messages = listOf(UIMessage.user("list files"))
        val firstStep = StreamChunkHandler(model)
        messages = firstStep.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "shell"))
        messages = firstStep.handle(
            messages,
            StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"cmd":"ls"}"""),
        )
        messages = firstStep.handle(messages, StreamChunk.ToolCallEnd("response:tool-0"))
        messages = firstStep.handle(messages, StreamChunk.Finish(finishReason = "tool_calls"))
        assertEquals(1, messages.tools().size)
        messages = messages.executed("response:tool-0", "a.txt")

        // 第二步：新的 handler，payload 复用同一个合成 id
        val secondStep = StreamChunkHandler(model)
        messages = secondStep.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "read_file"))
        messages = secondStep.handle(
            messages,
            StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"path":"a.txt"}"""),
        )
        messages = secondStep.handle(messages, StreamChunk.ToolCallEnd("response:tool-0"))
        messages = secondStep.handle(messages, StreamChunk.Finish(finishReason = "tool_calls"))

        val tools = messages.tools()
        assertEquals("每个生成的调用都必须有自己的 part", 2, tools.size)

        val executed = tools[0]
        assertEquals("response:tool-0", executed.toolCallId)
        assertEquals("shell", executed.toolName)
        assertEquals("旧调用的入参不能被本步拼接污染", """{"cmd":"ls"}""", executed.input)
        assertEquals("a.txt", (executed.output.single() as UIMessagePart.Text).text)
        assertTrue(executed.isExecuted)

        val pending = tools[1]
        assertNotEquals("复用 id 的本步调用必须换成本地唯一 id", executed.toolCallId, pending.toolCallId)
        assertEquals("read_file", pending.toolName)
        assertEquals("""{"path":"a.txt"}""", pending.input)
        assertFalse("本步的新调用必须待执行，而不是被当成已执行的老调用", pending.isExecuted)
    }

    @Test
    fun `parallel reused ids across steps keep every call isolated`() {
        var messages = listOf(UIMessage.user("run both"))
        val firstStep = StreamChunkHandler(model)
        messages = firstStep.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "shell"))
        messages = firstStep.handle(messages, StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"n":1}"""))
        messages = firstStep.handle(messages, StreamChunk.ToolCallStart("response:tool-1", "shell"))
        messages = firstStep.handle(messages, StreamChunk.ToolCallDelta("response:tool-1", inputDelta = """{"n":2}"""))
        messages = firstStep.handle(messages, StreamChunk.ToolCallEnd("response:tool-0"))
        messages = firstStep.handle(messages, StreamChunk.ToolCallEnd("response:tool-1"))
        messages = firstStep.handle(messages, StreamChunk.Finish(finishReason = "tool_calls"))
        messages = messages.executed("response:tool-0", "out-1").executed("response:tool-1", "out-2")

        val secondStep = StreamChunkHandler(model)
        messages = secondStep.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "shell"))
        messages = secondStep.handle(messages, StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"n":3}"""))
        messages = secondStep.handle(messages, StreamChunk.ToolCallStart("response:tool-1", "shell"))
        messages = secondStep.handle(messages, StreamChunk.ToolCallDelta("response:tool-1", inputDelta = """{"n":4}"""))
        messages = secondStep.handle(messages, StreamChunk.Finish(finishReason = "tool_calls"))

        val tools = messages.tools()
        assertEquals(4, tools.size)
        assertEquals("同一批里的 toolCallId 不允许重复", 4, tools.map { it.toolCallId }.toSet().size)
        assertEquals(
            listOf("""{"n":1}""", """{"n":2}""", """{"n":3}""", """{"n":4}"""),
            tools.map { it.input },
        )
        assertEquals("out-1", (tools[0].output.single() as UIMessagePart.Text).text)
        assertEquals("out-2", (tools[1].output.single() as UIMessagePart.Text).text)
        assertFalse(tools[2].isExecuted)
        assertFalse(tools[3].isExecuted)
    }

    @Test
    fun `reused id over an interrupted call keeps the pending call for resume`() {
        // 第一步的调用已经生成但没有 output（执行前被中断），恢复后同一个 id 再次出现
        var messages = listOf(
            UIMessage.user("list files"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "response:tool-0",
                        toolName = "shell",
                        input = """{"cmd":"ls"}""",
                    ),
                ),
            ),
        )

        val handler = StreamChunkHandler(model)
        messages = handler.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "shell"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"cmd":"pwd"}"""))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("response:tool-0"))

        val tools = messages.tools()
        assertEquals(2, tools.size)
        assertEquals("中断的调用不能被新调用吞掉", """{"cmd":"ls"}""", tools[0].input)
        assertFalse("中断的调用仍然待执行，恢复时不能倒退成已执行", tools[0].isExecuted)
        assertNotEquals(tools[0].toolCallId, tools[1].toolCallId)
        assertEquals("""{"cmd":"pwd"}""", tools[1].input)
    }

    @Test
    fun `duplicate start in the same step is a repeated event`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        // 同一步内重复的 Start：既不新建 part，也不让后续 Delta 把名字拼两遍
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", inputDelta = """{"q":"k"}"""))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tools = messages.tools()
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].toolName)
        assertEquals("""{"q":"k"}""", tools[0].input)
        assertEquals("call_1", tools[0].toolCallId)
    }

    @Test
    fun `streamed deltas merge into one part with the latest metadata`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)
        val startMetadata = metadata("start")
        val deltaMetadata = metadata("delta")

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "", startMetadata))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", toolNameDelta = "sear"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", toolNameDelta = "ch", inputDelta = """{"q":"""))
        messages = handler.handle(
            messages,
            StreamChunk.ToolCallDelta("call_1", inputDelta = """"kotlin"}""", metadata = deltaMetadata),
        )
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))

        val tool = messages.tools().single()
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"kotlin"}""", tool.input)
        assertEquals("delta", tool.metadata?.get("k")?.jsonPrimitive?.content)
    }

    @Test
    fun `unique provider id is kept verbatim for the wire protocol`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_abc123", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_abc123", inputDelta = """{"q":"k"}"""))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_abc123"))

        val tool = messages.tools().single()
        assertEquals("call_abc123", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"k"}""", tool.input)
    }

    @Test
    fun `tool call output only applies to the call of the current step`() {
        var messages = listOf(UIMessage.user("search"))
        val firstStep = StreamChunkHandler(model)
        messages = firstStep.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "web_search"))
        messages = firstStep.handle(messages, StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"q":"a"}"""))
        messages = firstStep.handle(messages, StreamChunk.ToolCallEnd("response:tool-0"))
        messages = firstStep.handle(messages, StreamChunk.ToolCallOutput("response:tool-0", "first-result"))
        messages = firstStep.handle(messages, StreamChunk.Finish())
        assertEquals("first-result", (messages.tools().single().output.single() as UIMessagePart.Text).text)

        val secondStep = StreamChunkHandler(model)
        messages = secondStep.handle(messages, StreamChunk.ToolCallStart("response:tool-0", "web_search"))
        messages = secondStep.handle(messages, StreamChunk.ToolCallDelta("response:tool-0", inputDelta = """{"q":"b"}"""))
        messages = secondStep.handle(messages, StreamChunk.ToolCallOutput("response:tool-0", "second-result"))

        val tools = messages.tools()
        assertEquals(2, tools.size)
        assertEquals("first-result", (tools[0].output.single() as UIMessagePart.Text).text)
        assertEquals("second-result", (tools[1].output.single() as UIMessagePart.Text).text)
        assertEquals("""{"q":"a"}""", tools[0].input)
        assertEquals("""{"q":"b"}""", tools[1].input)
    }

    @Test
    fun `delta without start on a reused id starts a new call`() {
        var messages = listOf(
            UIMessage.user("search"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "response:tool-0",
                        toolName = "search",
                        input = """{"q":"old"}""",
                        output = listOf(UIMessagePart.Text("old-result")),
                    ),
                ),
            ),
        )

        val handler = StreamChunkHandler(model)
        // 容忍 Provider 未发送 Start：直接建立新 part，但绝不能续写上面的已执行调用
        messages = handler.handle(
            messages,
            StreamChunk.ToolCallDelta("response:tool-0", toolNameDelta = "search", inputDelta = """{"q":"new"}"""),
        )

        val tools = messages.tools()
        assertEquals(2, tools.size)
        assertEquals("""{"q":"old"}""", tools[0].input)
        assertEquals("old-result", (tools[0].output.single() as UIMessagePart.Text).text)
        assertEquals("search", tools[1].toolName)
        assertEquals("""{"q":"new"}""", tools[1].input)
        assertFalse(tools[1].isExecuted)
    }

    @Test
    fun `reopened tool call in the same step keeps a single part`() {
        // Provider 可能在文本之后续写同一个 tool call：同一个 id 的 Start 会再来一次。
        // 同一步内这属于重复事件，必须继续合并到同一个 part，既不能建第二个 part（会执行两次），
        // 也不能把工具名拼两遍。
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", inputDelta = """{"q":"a"""))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call_1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call_1", "search"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call_1", inputDelta = """}"""))

        val tool = messages.tools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"a"}""", tool.input)
    }

    private fun metadata(value: String): JsonObject = buildJsonObject { put("k", value) }

    private fun List<UIMessage>.tools(): List<UIMessagePart.Tool> =
        last().parts.filterIsInstance<UIMessagePart.Tool>()

    /** 模拟 AgentLoop 执行完一次工具调用：把 output 写到对应的 part 上。 */
    private fun List<UIMessage>.executed(toolCallId: String, output: String): List<UIMessage> =
        mapIndexed { index, message ->
            if (index != lastIndex) {
                message
            } else {
                message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                        part.copy(output = listOf(UIMessagePart.Text(output)))
                    } else {
                        part
                    }
                })
            }
        }
}
