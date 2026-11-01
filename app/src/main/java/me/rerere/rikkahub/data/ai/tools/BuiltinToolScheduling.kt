package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode

// Never classify MCP tools by their apparent name: names do not establish side-effect semantics.
private val independentReads = setOf(
    "memory_read", "memory_list", "search_web", "recent_chats", "conversation_search", "conversation_read",
    "workspace_read_file", "use_skill", "eval_javascript", "get_current_time", "get_time_info",
    "calendar_query", "get_screen_time",
)
private val concurrentWithoutCoalescing = setOf(
    "workspace_job_status", "subagent_run", "subagent_wait", "subagent_list",
)

internal fun withBuiltinExecutionMode(tool: Tool): Tool = when {
    tool.name.startsWith(MCP_TOOL_NAME_PREFIX) -> tool
    tool.name in independentReads -> tool.copy(executionMode = ToolExecutionMode.PARALLEL_DEDUPLICATED)
    tool.name in concurrentWithoutCoalescing -> tool.copy(executionMode = ToolExecutionMode.PARALLEL)
    else -> tool
}
