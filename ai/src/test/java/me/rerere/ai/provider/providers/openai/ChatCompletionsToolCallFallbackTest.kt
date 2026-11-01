package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 很多自建/中转/本地 OpenAI 兼容端点在流里既不返回响应 `id`，也不返回 `tool_calls[].id`。
 * 合成的调用 id 必须每实例（= 每次响应 = 多步 agent 的每一步）都不同，否则第二步会撞上
 * 第一步已经执行过的 part，把新调用吞掉。这些用例锁定合成规则与端到端结果。
 */
class ChatCompletionsToolCallFallbackTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `tool ids without response id differ between responses but stay stable inside one`() {
        val first = toolCallIds(
            collect(
                ChatCompletionsStreamDecoder(),
                toolSse(ToolDelta(index = 0, name = "shell", arguments = """{"cmd":"ls"}""")),
            ),
        )
        val second = toolCallIds(
            collect(
                ChatCompletionsStreamDecoder(),
                toolSse(ToolDelta(index = 0, name = "shell", arguments = """{"cmd":"ls"}""")),
            ),
        )

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertNotEquals("不同响应不能用同一个合成 id", first.single(), second.single())

        // 同一个响应内，同一 index 的多次增量必须落在同一个 id 上
        val splitChunks = collect(
            ChatCompletionsStreamDecoder(),
            toolSse(ToolDelta(index = 0, name = "shell", arguments = """{"cmd":""")),
            toolSse(ToolDelta(index = 0, arguments = """"ls"}""")),
            toolSse(ToolDelta(index = 1, name = "read_file", arguments = """{"path":"a.txt"}""")),
        )
        val starts = splitChunks.filterIsInstance<StreamChunk.ToolCallStart>().map { it.id }
        assertEquals(2, starts.size)
        assertEquals(2, starts.toSet().size)
        assertEquals(
            "同一调用的增量必须用 Start 分配的 id",
            setOf(starts[0]),
            splitChunks.filterIsInstance<StreamChunk.ToolCallDelta>()
                .map { it.id }
                .filter { it != starts[1] }
                .toSet(),
        )
    }

    @Test
    fun `response id keeps the previous synthesised id format`() {
        val ids = toolCallIds(
            collect(
                ChatCompletionsStreamDecoder(),
                toolSse(ToolDelta(index = 0, name = "shell", arguments = "{}"), responseId = "resp-1"),
            ),
        )

        assertEquals(listOf("resp-1:tool-0"), ids)
    }

    @Test
    fun `explicit provider tool id is preserved`() {
        val chunks = collect(
            ChatCompletionsStreamDecoder(),
            toolSse(ToolDelta(index = 0, id = "call_abc", name = "shell", arguments = "{}")),
        )

        assertEquals(listOf("call_abc"), toolCallIds(chunks))
        assertEquals("shell", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().toolName)
    }

    @Test
    fun `blank provider tool id is treated as missing`() {
        val chunks = collect(
            ChatCompletionsStreamDecoder(),
            toolSse(ToolDelta(index = 0, id = "", name = "shell", arguments = "{}")),
            toolSse(ToolDelta(index = 1, id = "", name = "read_file", arguments = "{}")),
        )

        val ids = toolCallIds(chunks)
        assertEquals(2, ids.size)
        assertTrue("空 id 不能直接当成 tool_call_id", ids.none { it.isBlank() })
        assertEquals("空 id 也要按 index 区分", 2, ids.toSet().size)
    }

    @Test
    fun `second step without response id does not corrupt the executed call`() {
        // 第一步：没有响应 id、没有调用 id，agent 执行完工具
        var messages = listOf(UIMessage.user("list files"))
        val firstHandler = StreamChunkHandler(model)
        collect(
            ChatCompletionsStreamDecoder(),
            toolSse(ToolDelta(index = 0, name = "shell", arguments = """{"cmd":"ls"}""")),
        ).forEach { chunk -> messages = firstHandler.handle(messages, chunk) }

        val firstTool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("shell", firstTool.toolName)
        assertEquals("""{"cmd":"ls"}""", firstTool.input)
        messages = messages.executed(firstTool.toolCallId, "a.txt")

        // 第二步：全新的 decoder + handler，payload 与第一步逐字节相同
        val secondHandler = StreamChunkHandler(model)
        collect(
            ChatCompletionsStreamDecoder(),
            toolSse(ToolDelta(index = 0, name = "read_file", arguments = """{"path":"a.txt"}""")),
        ).forEach { chunk -> messages = secondHandler.handle(messages, chunk) }

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals("第二步必须生成新的 part", 2, tools.size)
        assertEquals("第一步的 id 不能被改写", firstTool.toolCallId, tools[0].toolCallId)
        assertEquals("""{"cmd":"ls"}""", tools[0].input)
        assertEquals("a.txt", (tools[0].output.single() as UIMessagePart.Text).text)
        assertNotEquals("复用 id 时第二步要拿到新的本地 id", tools[0].toolCallId, tools[1].toolCallId)
        assertEquals("read_file", tools[1].toolName)
        assertEquals("""{"path":"a.txt"}""", tools[1].input)
        assertFalse("第二步的调用必须待执行", tools[1].isExecuted)
    }

    @Test
    fun `finish and tool end still arrive for id less tool calls`() {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = decoder.accept(
            toolSse(ToolDelta(index = 0, name = "shell", arguments = "{}"), finishReason = "tool_calls"),
        ).chunks + decoder.onClosed()

        assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
        val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("tool_calls", finish.finishReason)
    }

    private data class ToolDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    )

    private fun toolSse(
        vararg toolCalls: ToolDelta,
        responseId: String? = null,
        finishReason: String? = null,
    ): SseEvent = SseEvent(
        data = buildJsonObject {
            responseId?.let { put("id", it) }
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("index", call.index)
                                    call.id?.let { put("id", it) }
                                    put("function", buildJsonObject {
                                        call.name?.let { put("name", it) }
                                        call.arguments?.let { put("arguments", it) }
                                    })
                                })
                            }
                        })
                    })
                    put("finish_reason", finishReason)
                })
            })
        }.toString(),
    )

    private fun collect(decoder: StreamChunkDecoder, vararg events: SseEvent): List<StreamChunk> =
        events.flatMap { decoder.accept(it).chunks } + decoder.onClosed()

    private fun toolCallIds(chunks: List<StreamChunk>): List<String> =
        chunks.filterIsInstance<StreamChunk.ToolCallStart>().map { it.id }

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
