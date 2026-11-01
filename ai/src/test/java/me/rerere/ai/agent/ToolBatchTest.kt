package me.rerere.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * 锁定 [createToolBatch] 的契约：
 *
 * - 参数校验先于任何副作用（有一项非法时一项都不执行）；
 * - 结果按调用顺序返回，与完成先后无关；
 * - 只读调用在批内并发，写调用是完整的顺序屏障；
 * - 上限 16、不能嵌套 `tool_batch` / `ask_user` / `run_script`；
 * - 批内审批合并，且审批判定本身失败时失败关闭（要求审批）；
 * - 单项失败不影响其他项；
 * - 取消向上传播，不被当成"单项失败"吞掉。
 *
 * 只通过 `Tool.execute` / `Tool.needsApproval` 的公开入口断言，不依赖实现细节；并发用 runTest
 * 的虚拟时钟度量，因此"并发 vs 串行"的耗时差异是确定性的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolBatchTest {
    // ------------------------------------------------------------ 参数校验先于副作用

    @Test
    fun `an unknown member rejects the batch before anything runs`() = runTest {
        val executed = mutableListOf<String>()
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            executed += "read"
            text("read")
        }
        val batch = createToolBatch(listOf(read))

        val input = batchInput(call("read", """{"path":"a"}"""), call("missing"))
        // 非法输入不会造成审批死等：执行阶段会直接失败，并且没有副作用
        assertFalse(batch.needsApproval(input))
        val error = runCatching { batch.execute(input) }.exceptionOrNull()

        assertNotNull("未知工具必须让整批失败", error)
        assertTrue(
            "错误信息要说明批里没有这项：${error?.message}",
            error?.message.orEmpty().contains("not available"),
        )
        assertTrue("校验失败时一项都不能执行", executed.isEmpty())
    }

    @Test
    fun `a non object arguments value rejects the whole batch`() = runTest {
        val executed = mutableListOf<String>()
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            executed += "read"
            text("read")
        }
        var approvals = 0
        val write = batchTool("write", ToolExecutionMode.SERIAL, needsApproval = {
            approvals++
            true
        }) {
            executed += "write"
            text("write")
        }
        val batch = createToolBatch(listOf(read, write))

        val input = buildJsonObject {
            put("calls", buildJsonArray {
                add(buildJsonObject {
                    put("name", JsonPrimitive("read"))
                    put("arguments", buildJsonObject { put("path", "a") })
                })
                add(buildJsonObject {
                    put("name", JsonPrimitive("write"))
                    put("arguments", JsonPrimitive("not an object"))
                })
            })
        }

        assertFalse(batch.needsApproval(input))
        val error = runCatching { batch.execute(input) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("arguments must be an object"))
        assertTrue("第一项合法也不能先跑", executed.isEmpty())
        assertEquals("校验失败时也不该去问成员的审批", 0, approvals)
    }

    @Test
    fun `an empty call list is rejected`() = runTest {
        val batch = createToolBatch(listOf(batchTool("read", ToolExecutionMode.PARALLEL) { text("read") }))

        val error = runCatching { batch.execute(buildJsonObject { put("calls", buildJsonArray { }) }) }
            .exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("1..16"))
    }

    @Test
    fun `more than sixteen calls are rejected without running anything`() = runTest {
        val runs = AtomicInteger()
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            runs.incrementAndGet()
            text("read")
        }
        val batch = createToolBatch(listOf(read))

        val input = batchInput(*Array(17) { call("read", """{"i":$it}""") })
        assertFalse(batch.needsApproval(input))
        val error = runCatching { batch.execute(input) }.exceptionOrNull()

        assertNotNull("超过 16 项必须直接拒绝", error)
        assertTrue(error?.message.orEmpty().contains("1..16"))
        assertEquals(0, runs.get())
    }

    // ------------------------------------------------------------ 嵌套与排除

    @Test
    fun `tool_batch ask_user and run_script are never batchable`() = runTest {
        val executed = mutableListOf<String>()
        val nested = batchTool("tool_batch", ToolExecutionMode.SERIAL) {
            executed += "tool_batch"
            text("nested")
        }
        val ask = batchTool("ask_user", ToolExecutionMode.SERIAL) {
            executed += "ask_user"
            text("asked")
        }
        val script = batchTool("run_script", ToolExecutionMode.SERIAL) {
            executed += "run_script"
            text("ran")
        }
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            executed += "read"
            text("read")
        }
        // 即使上层把它们放进候选列表，也不能出现在 catalog 里
        val batch = createToolBatch(listOf(nested, ask, script, read))

        listOf("tool_batch", "ask_user", "run_script").forEach { name ->
            val error = runCatching { batch.execute(batchInput(call(name))) }.exceptionOrNull()
            assertNotNull("$name 不能被批内调用", error)
            assertTrue(
                "$name 的错误要说明不可批处理：${error?.message}",
                error?.message.orEmpty().contains("not available for batching"),
            )
        }
        assertTrue(executed.isEmpty())
        // 普通成员仍然可批
        val results = batch.execute(batchInput(call("read"))).results()
        assertEquals(1, results.size)
        assertEquals("read", results[0].resultName())
    }

    // ------------------------------------------------------------ 顺序与并发

    @Test
    fun `results keep call order even when a later call finishes first`() = runTest {
        val slow = batchTool("slow", ToolExecutionMode.PARALLEL) {
            delay(500)
            text("slow:" + argString(it, "n"))
        }
        val fast = batchTool("fast", ToolExecutionMode.PARALLEL) {
            delay(10)
            text("fast:" + argString(it, "n"))
        }
        val batch = createToolBatch(listOf(slow, fast))

        val results = batch.execute(
            batchInput(call("slow", """{"n":"1"}"""), call("fast", """{"n":"2"}""")),
        ).results()

        assertEquals(2, results.size)
        assertEquals(0, results[0].resultIndex())
        assertEquals("slow", results[0].resultName())
        assertEquals(listOf("slow:1"), results[0].resultOutputs())
        assertEquals(1, results[1].resultIndex())
        assertEquals("fast", results[1].resultName())
        assertEquals(listOf("fast:2"), results[1].resultOutputs())
    }

    @Test
    fun `adjacent reads overlap inside one batch`() = runTest {
        val delayMs = 300L
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            delay(delayMs)
            text("data:" + argString(it, "path"))
        }
        val batch = createToolBatch(listOf(read))

        val startedAt = testScheduler.currentTime
        val results = batch.execute(
            batchInput(call("read", """{"path":"a"}"""), call("read", """{"path":"b"}""")),
        ).results()
        val elapsed = testScheduler.currentTime - startedAt

        assertTrue("耗时应至少是一倍延迟，实际 $elapsed", elapsed >= delayMs)
        assertTrue("只读调用没有并发：$elapsed", elapsed < delayMs * 2)
        assertEquals(listOf("data:a"), results[0].resultOutputs())
        assertEquals(listOf("data:b"), results[1].resultOutputs())
    }

    @Test
    fun `a write between reads is a full ordering barrier inside the batch`() = runTest {
        val log = mutableListOf<String>()
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            val id = argString(it, "id")
            log += "read$id-start"
            delay(100)
            log += "read$id-end"
            text("read$id")
        }
        val write = batchTool("write", ToolExecutionMode.SERIAL) {
            val id = argString(it, "id")
            log += "write$id-start"
            delay(100)
            log += "write$id-end"
            text("write$id")
        }
        val batch = createToolBatch(listOf(read, write))

        batch.execute(
            batchInput(
                call("read", """{"id":"1"}"""),
                call("write", """{"id":"2"}"""),
                call("read", """{"id":"3"}"""),
            ),
        )

        assertEquals(
            listOf("read1-start", "read1-end", "write2-start", "write2-end", "read3-start", "read3-end"),
            log,
        )
    }

    @Test
    fun `deduplicated members share one execution and both keep the result`() = runTest {
        val runs = AtomicInteger()
        val read = batchTool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            runs.incrementAndGet()
            delay(50)
            text("payload")
        }
        val batch = createToolBatch(listOf(read))

        val results = batch.execute(
            batchInput(
                call("read", """{"path":"a","depth":2}"""),
                call("read", """{"depth":2,"path":"a"}"""),
            ),
        ).results()

        assertEquals("键顺序不同但参数相同，只应执行一次", 1, runs.get())
        assertEquals(listOf("payload"), results[0].resultOutputs())
        assertEquals(listOf("payload"), results[1].resultOutputs())
    }

    @Test
    fun `plain parallel members with identical arguments still run twice`() = runTest {
        val runs = AtomicInteger()
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            val order = runs.incrementAndGet()
            text("run$order")
        }
        val batch = createToolBatch(listOf(read))

        batch.execute(batchInput(call("read", """{"path":"a"}"""), call("read", """{"path":"a"}""")))

        assertEquals("非 DEDUP 的并发成员不能被合并", 2, runs.get())
    }

    // ------------------------------------------------------------ 单项失败

    @Test
    fun `a failing member does not stop the other members`() = runTest {
        val executed = mutableListOf<String>()
        val boom = batchTool("boom", ToolExecutionMode.SERIAL) {
            executed += "boom"
            error("kaboom")
        }
        val read = batchTool("read", ToolExecutionMode.SERIAL) {
            executed += "read"
            text("fine")
        }
        val batch = createToolBatch(listOf(boom, read))

        val results = batch.execute(batchInput(call("boom"), call("read"))).results()

        assertEquals("单项失败后其余成员仍要执行", listOf("boom", "read"), executed)
        val failure = results[0].resultOutputs().joinToString("")
        assertTrue("失败要作为 error 字段回给模型：$failure", failure.contains("error"))
        assertTrue("要带上异常信息：$failure", failure.contains("kaboom"))
        assertEquals(listOf("fine"), results[1].resultOutputs())
    }

    @Test
    fun `an empty member output is replaced by a placeholder`() = runTest {
        val silent = batchTool("silent", ToolExecutionMode.SERIAL) { emptyList() }
        val batch = createToolBatch(listOf(silent))

        val results = batch.execute(batchInput(call("silent"))).results()

        assertEquals(listOf("(no output)"), results[0].resultOutputs())
    }

    @Test
    fun `non text member output is attached to the batch and counted`() = runTest {
        val snap = batchTool("snap", ToolExecutionMode.SERIAL) {
            listOf(
                UIMessagePart.Image(url = "data:image/png;base64,AAAA"),
                UIMessagePart.Text("screenshot"),
            )
        }
        val batch = createToolBatch(listOf(snap))

        val parts = batch.execute(batchInput(call("snap")))
        val results = parts.results()

        assertEquals(listOf("screenshot"), results[0].resultOutputs())
        assertEquals(1, results[0].jsonObject.getValue("attachedParts").jsonPrimitive.int)
        assertTrue("图片 part 要平铺回父 part，不能丢", parts.any { it is UIMessagePart.Image })
    }

    // ------------------------------------------------------------ 审批

    @Test
    fun `approval is aggregated from the member arguments`() = runTest {
        val asked = mutableListOf<String>()
        val write = batchTool("write", ToolExecutionMode.SERIAL, needsApproval = { input ->
            asked += input.toString()
            true
        }) {
            text("written")
        }
        val read = batchTool("read", ToolExecutionMode.PARALLEL) { text("read") }
        val batch = createToolBatch(listOf(read, write))

        assertTrue(
            "批里有一项需要审批，整批都要审批",
            batch.needsApproval(
                batchInput(call("read", """{"path":"a"}"""), call("write", """{"path":"out"}""")),
            ),
        )
        assertTrue("成员的审批判定必须收到成员自己的参数：$asked", asked.single().contains("\"path\":\"out\""))
        assertFalse(
            "全员无需审批的批不能要求审批",
            batch.needsApproval(batchInput(call("read", """{"path":"a"}"""))),
        )
    }

    @Test
    fun `an approval check that throws requires approval`() = runTest {
        val executed = mutableListOf<String>()
        val broken = batchTool("broken", ToolExecutionMode.SERIAL, needsApproval = {
            error("no approval today")
        }) {
            executed += "broken"
            text("ran")
        }
        val read = batchTool("read", ToolExecutionMode.PARALLEL) {
            executed += "read"
            text("read")
        }
        val batch = createToolBatch(listOf(read, broken))

        val input = batchInput(call("read", """{"path":"a"}"""), call("broken"))
        // 审批判定失败 = 不知道要不要审批，必须失败关闭
        assertTrue("成员的 needsApproval 抛异常时必须要求审批", batch.needsApproval(input))
        assertTrue("判定阶段不能有任何副作用", executed.isEmpty())
    }

    // ------------------------------------------------------------ 取消

    @Test
    fun `a cancelled member cancels the whole batch instead of failing alone`() = runTest {
        val started = CompletableDeferred<Unit>()
        val slow = batchTool("slow", ToolExecutionMode.SERIAL) {
            started.complete(Unit)
            awaitCancellation()
        }
        val batch = createToolBatch(listOf(slow))
        val input = batchInput(call("slow"))

        var completed = false
        var thrown: Throwable? = null
        val job = launch {
            try {
                batch.execute(input)
                completed = true
            } catch (e: Throwable) {
                thrown = e
            }
        }
        started.await()
        job.cancelAndJoin()

        assertFalse("取消不能被当成单项失败，整批不能返回结果", completed)
        assertTrue("取消必须向上传播，实际 $thrown", thrown is CancellationException)
    }
}

private fun batchTool(
    name: String,
    executionMode: ToolExecutionMode,
    needsApproval: (JsonElement) -> Boolean = { false },
    execute: suspend (JsonElement) -> List<UIMessagePart>,
) = Tool(
    name = name,
    description = name,
    needsApproval = needsApproval,
    executionMode = executionMode,
    execute = execute,
)

private fun batchInput(vararg items: Pair<String, JsonElement>): JsonElement = buildJsonObject {
    put("calls", buildJsonArray {
        items.forEach { (name, arguments) ->
            add(buildJsonObject {
                put("name", JsonPrimitive(name))
                put("arguments", arguments)
            })
        }
    })
}

private fun call(name: String, rawArguments: String = "{}"): Pair<String, JsonElement> =
    name to json.parseToJsonElement(rawArguments)

private fun text(value: String): List<UIMessagePart> = listOf(UIMessagePart.Text(value))

private fun argString(args: JsonElement, key: String): String =
    args.jsonObject[key]?.jsonPrimitive?.content.orEmpty()

/** 批的输出是 `{"results":[{index,name,output[]}]}`，放在第一个 Text part 里。 */
private fun List<UIMessagePart>.results(): JsonArray =
    json.parseToJsonElement(filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        .jsonObject.getValue("results").jsonArray

private fun JsonElement.resultIndex(): Int = jsonObject.getValue("index").jsonPrimitive.int

private fun JsonElement.resultName(): String = jsonObject.getValue("name").jsonPrimitive.content

private fun JsonElement.resultOutputs(): List<String> =
    jsonObject.getValue("output").jsonArray.map { it.jsonPrimitive.content }
