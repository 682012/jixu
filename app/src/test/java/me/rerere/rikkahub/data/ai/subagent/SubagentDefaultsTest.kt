package me.rerere.rikkahub.data.ai.subagent

import me.rerere.rikkahub.data.datastore.SubagentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 子代理默认值与分页常量的契约：默认 4、硬上限 8、单页默认 6000 / 上限 12000。 */
class SubagentDefaultsTest {
    @Test
    fun `default max concurrent is four and stays under the hard cap`() {
        assertEquals(4, SubagentSettings().maxConcurrent)
        assertTrue(SubagentSettings().maxConcurrent <= 8)
    }

    @Test
    fun `page limits are 6000 by default and 12000 at most`() {
        assertEquals(6_000, SUBAGENT_RESULT_DEFAULT_LIMIT)
        assertEquals(12_000, SUBAGENT_RESULT_MAX_LIMIT)
        assertTrue(SUBAGENT_RESULT_DEFAULT_LIMIT < SUBAGENT_RESULT_MAX_LIMIT)
        assertEquals(8, SUBAGENT_MAX_WAIT_TASKS)
    }
}
