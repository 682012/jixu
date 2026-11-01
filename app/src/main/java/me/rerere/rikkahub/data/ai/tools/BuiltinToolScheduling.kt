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

// 轮询类工具：同样的参数反复调用、结果相同是正常的，不折叠输出也不按重复调用拦截（见 ai 模块 ToolRepetition.kt）
private val pollingTools = setOf(
    "workspace_job_status", "workspace_shell_output", "subagent_wait", "subagent_list", "get_current_time", "get_time_info",
)

internal fun withBuiltinExecutionMode(tool: Tool): Tool {
    if (tool.name.startsWith(MCP_TOOL_NAME_PREFIX)) return tool
    val mode = when (tool.name) {
        in independentReads -> ToolExecutionMode.PARALLEL_DEDUPLICATED
        in concurrentWithoutCoalescing -> ToolExecutionMode.PARALLEL
        else -> tool.executionMode
    }
    val repeatable = tool.repeatable || tool.name in pollingTools
    return if (mode == tool.executionMode && repeatable == tool.repeatable) tool
    else tool.copy(executionMode = mode, repeatable = repeatable)
}
