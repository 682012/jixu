package me.rerere.rikkahub.data.ai.subagent

import kotlin.uuid.Uuid
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结果分页/通知文本/交付记录都是纯函数，直接在这里锁行为。 */
class SubagentResultPagingTest {
    private fun task(
        status: SubagentStatus = SubagentStatus.COMPLETED,
        steps: Int = 3,
        prompt: String = "do the thing",
    ) = SubagentTask(
        id = "task-1",
        prompt = prompt,
        modelId = null,
        conversationId = Uuid.random(),
        status = status,
        startedAt = 1_000L,
        finishedAt = 11_000L,
        steps = steps,
    )

    @Test
    fun `default page returns first 6000 chars with clear paging fields`() {
        val full = "a".repeat(20_000)
        val page = subagentResultPage(task(), full, offset = 0, limit = 0)

        assertEquals(6_000, page.limit)
        assertEquals(6_000, page.text.length)
        assertEquals(20_000, page.totalChars)
        assertEquals(0, page.offset)
        assertEquals(6_000, page.nextOffset)
        assertFalse(page.eof)
        assertTrue(page.truncated)
    }

    @Test
    fun `last page reports eof and a nextOffset at the end`() {
        val full = "a".repeat(20_000)
        val page = subagentResultPage(task(), full, offset = 18_000, limit = 6_000)

        assertEquals(2_000, page.text.length)
        assertEquals(20_000, page.nextOffset)
        assertTrue(page.eof)
        assertFalse(page.truncated)
    }

    @Test
    fun `limit is capped at 12000 and offset is clamped`() {
        val full = "a".repeat(50_000)
        assertEquals(12_000, subagentResultPage(task(), full, 0, 99_999).text.length)

        val beyond = subagentResultPage(task(), full, offset = 80_000, limit = 6_000)
        assertEquals(full.length, beyond.offset)
        assertEquals("", beyond.text)
        assertTrue(beyond.eof)

        val negative = subagentResultPage(task(), full, offset = -5, limit = 10)
        assertEquals(0, negative.offset)
        assertEquals(10, negative.text.length)
    }

    @Test
    fun `empty or missing result still yields a readable empty page`() {
        val page = subagentResultPage(task(status = SubagentStatus.FAILED), "", 0, 6_000)
        assertEquals(0, page.totalChars)
        assertEquals("", page.text)
        assertTrue(page.eof)
    }

    @Test
    fun `page json carries paging fields and a read hint when more remains`() {
        val page = subagentResultPage(task(), "b".repeat(9_000), 0, 6_000)
        val json = page.toJsonObject()

        assertEquals(9_000, json["totalChars"]!!.jsonPrimitive.int)
        assertEquals(6_000, json["nextOffset"]!!.jsonPrimitive.int)
        assertFalse(json["eof"]!!.jsonPrimitive.boolean)
        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(6_000, json["returnedChars"]!!.jsonPrimitive.int)
        assertTrue(json["readHint"]!!.jsonPrimitive.content.contains("offset=6000"))
        assertEquals(6_000, json["result"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `notice carries ids and sizes but never the body`() {
        val body = "x".repeat(400) + "TAIL-MARKER-" + "y".repeat(2_000)
        val notice = buildSubagentNotice(task(status = SubagentStatus.COMPLETED, steps = 7), body)

        val text = notice.text
        assertTrue(text.contains("task-1"))
        assertTrue(text.contains("COMPLETED"))
        assertTrue(text.contains("7 step(s)"))
        assertTrue(text.contains("${body.length} result chars"))
        assertTrue(text.contains("subagent_result(task_id=\"task-1\")"))
        assertFalse(text.contains("TAIL-MARKER-"))
        assertTrue(text.length < 1_200)
        assertTrue(notice.title.contains("COMPLETED"))
    }

    @Test
    fun `failure notice mentions the error and still points at the partial result`() {
        val failed = task(status = SubagentStatus.TIMEOUT).copy(error = "Subagent timed out after 900s")
        val notice = buildSubagentNotice(failed, "partial output")

        assertTrue(notice.text.contains("TIMEOUT"))
        assertTrue(notice.text.contains("Subagent timed out after 900s"))
        assertTrue(notice.text.contains("partial output"))
        assertTrue(notice.text.contains("subagent_result(task_id=\"task-1\")"))
    }

    @Test
    fun `only the first transition into a terminal state notifies`() {
        val running = task(status = SubagentStatus.RUNNING)
        val completed = running.copy(status = SubagentStatus.COMPLETED)

        assertTrue(isSubagentTerminalTransition(running, completed))
        assertTrue(isSubagentTerminalTransition(running, running.copy(status = SubagentStatus.TIMEOUT)))
        assertTrue(isSubagentTerminalTransition(running, running.copy(status = SubagentStatus.CANCELLED)))
        assertFalse(isSubagentTerminalTransition(completed, completed))
        assertFalse(isSubagentTerminalTransition(completed, completed.copy(status = SubagentStatus.FAILED)))
        assertFalse(isSubagentTerminalTransition(task(status = SubagentStatus.QUEUED), running))
    }

    @Test
    fun `delivery registry remembers tasks and can be cleared`() {
        val registry = SubagentDeliveryRegistry()
        assertFalse(registry.isDelivered("a"))
        registry.markDelivered("a")
        assertTrue(registry.isDelivered("a"))
        assertEquals("subagent:a", subagentNoticeKey("a"))
        registry.forget("a")
        assertFalse(registry.isDelivered("a"))
    }

    @Test
    fun `wait ids accept both forms, keep order, dedupe and reject overload`() {
        assertEquals(listOf("a"), resolveWaitTaskIds(null, "a"))
        assertEquals(listOf("a", "b"), resolveWaitTaskIds(listOf("a", "b"), null))
        assertEquals(listOf("a", "b"), resolveWaitTaskIds(listOf(" a", "b", "a "), null))
        assertEquals(listOf("a", "b"), resolveWaitTaskIds(listOf("a"), "b"))

        runCatching { resolveWaitTaskIds(emptyList(), "  ") }.onSuccess { error("empty ids must fail") }
        runCatching { resolveWaitTaskIds((1..9).map { "t$it" }, null) }
            .onSuccess { error("more than $SUBAGENT_MAX_WAIT_TASKS ids must fail") }
        assertEquals(
            SUBAGENT_MAX_WAIT_TASKS,
            resolveWaitTaskIds((1..SUBAGENT_MAX_WAIT_TASKS).map { "t$it" }, null).size,
        )
    }
}
