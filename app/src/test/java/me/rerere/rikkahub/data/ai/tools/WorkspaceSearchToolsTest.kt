package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspace_grep / workspace_glob 的调度与审批约定。
 *
 * 三个读/搜工具都是只读、自己分页的: 默认不打断用户, 可并行发出, 也不再经 ToolOutputStore 外置。
 */
class WorkspaceSearchToolsTest {
    private fun tool(name: String) = Tool(name = name, description = "") { emptyList() }

    @Test
    fun `search tools are read only and need no approval by default`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_grep", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_glob", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        // 仍可按工作区覆盖
        assertTrue(resolveWorkspaceToolApproval("workspace_grep", mapOf("workspace_grep" to true)))
        assertTrue(resolveWorkspaceToolApproval("workspace_glob", mapOf("workspace_glob" to true)))
    }

    @Test
    fun `search tools run in parallel with independent reads`() {
        listOf("workspace_grep", "workspace_glob", "workspace_read_file").forEach { name ->
            assertEquals(
                name,
                ToolExecutionMode.PARALLEL_DEDUPLICATED,
                withBuiltinExecutionMode(tool(name)).executionMode,
            )
        }
    }

    @Test
    fun `search tools are exempt from tool output offloading`() {
        assertTrue("workspace_grep" in SELF_PAGED_TOOL_NAMES)
        assertTrue("workspace_glob" in SELF_PAGED_TOOL_NAMES)
        assertTrue("workspace_read_file" in SELF_PAGED_TOOL_NAMES)
        // shell 输出体积不可控, 仍然外置
        assertFalse("workspace_shell" in SELF_PAGED_TOOL_NAMES)
    }
}
