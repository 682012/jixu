package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceExecutionBackend
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.effectiveExecutionBackend
import me.rerere.rikkahub.data.repository.isToolAvailableOnBackend
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.repository.workspaceToolsReady
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.BackgroundShellManager
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

// 能读到 /tool_outputs 这个挂载的工具：RepositoryModule 把 filesDir/tool_outputs 挂到 /tool_outputs，
// 同一份挂载表也用于文件工具的路径解析。子代理手里没有其中之一时，截断提示里的路径它读不到。
private val TOOL_OUTPUT_READER_TOOLS = setOf("workspace_shell", "workspace_read_file")

/**
 * 子代理是否也应该把超长工具输出外置成文件。
 *
 * 旧行为：只有父对话截断，子代理拿全量输出，一次大 dump 就撑爆它自己的上下文。
 * 但无条件给子代理套截断同样有害——没有工作区、走远程工作区、或子代理设置里禁用了
 * workspace 工具时，子代理只会看到"全文已存到 /tool_outputs/xxx.txt"，却没有任何工具
 * 能读那个文件，信息从可用变成不可读。
 *
 * 因此要求同时满足两条：父对话具备本地工作区文件访问能力，且子代理实际拿到的工具里
 * 有能读 /tool_outputs 的那个。
 *
 * @param localWorkspaceFileAccess 本地工作区可读 /tool_outputs（即父侧 canReadToolOutputs）
 * @param childTools 子代理最终会拿到的工具（已按 isSubagentToolAllowed 过滤）
 */
internal fun shouldTruncateChildToolOutput(
    localWorkspaceFileAccess: Boolean,
    childTools: List<Tool>,
): Boolean = localWorkspaceFileAccess && childTools.any { it.name in TOOL_OUTPUT_READER_TOOLS }

/**
 * 父对话/子代理能否读回被外置的全文。
 *
 * `/tool_outputs` 由 RepositoryModule 挂进本地 PRoot Rootfs 的挂载表，只有 LOCAL 后端能 `cat` 到；
 * HTTP 远程的 shell 跑在服务器上，SSH 走 SFTP，两者都读不到本机 filesDir，外置只会把
 * “可用输出”变成“一条读不到的路径”。所以这里必须按有效后端判断，而不是只看工作区有没有 rootfs。
 *
 * @param backend 有效执行后端（[effectiveExecutionBackend]），无工作区时为 null
 * @param workspaceTools 本次真正给出的 workspace 工具，空表示连 shell 都没有
 * @param workspaceHasRootfs 工作区能力里的 rootfs
 */
internal fun canReadToolOutputs(
    backend: WorkspaceExecutionBackend?,
    workspaceTools: List<Tool>,
    workspaceHasRootfs: Boolean,
): Boolean = backend == WorkspaceExecutionBackend.LOCAL_ROOTFS &&
    workspaceTools.isNotEmpty() &&
    workspaceHasRootfs

// fork: 后台 shell 任务归属父会话，子代理不拿这两个工具
private val BACKGROUND_SHELL_TOOLS = setOf("workspace_shell_output", "workspace_shell_kill")

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val subagentRunner: me.rerere.rikkahub.data.ai.subagent.SubagentRunner,
    private val backgroundShellManager: BackgroundShellManager,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> {
        // fork: 工作区实体只取一次，后端选择、工具可见性与输出外置共用同一份判断
        val workspace = assistant.workspaceId?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { workspaceRepository.getById(it) }
        val backend = effectiveExecutionBackend(workspace, settings.workspaceRuntime)
        val workspaceTools = createWorkspaceToolsIfReady(
            workspace = workspace,
            cwd = workspaceCwd,
            runtime = settings.workspaceRuntime,
            conversationId = conversationId,
            backgroundShellManager = backgroundShellManager,
        )
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell / 读不到本地文件区时只能原样返回。
        // 文件放在本机 filesDir，只有 LOCAL Rootfs 挂载了 /tool_outputs：HTTP 远程与 SSH 都读不到。
        val canReadLocalOutputs = canReadToolOutputs(
            backend = backend,
            workspaceTools = workspaceTools,
            workspaceHasRootfs = workspace?.capabilities()?.rootfs == true,
        )
        // fork: 子代理工具。childTools 里不含 subagent_*，避免无限嵌套。
        // 子代理可以额外拿到会话检索/读取工具，用来参考其他聊天。
        val childBaseTools = if (settings.subagent.enabled && workspaceTools.any { it.name in BACKGROUND_SHELL_TOOLS }) {
            val plain = createWorkspaceToolsIfReady(
                workspace = workspace,
                cwd = workspaceCwd,
                runtime = settings.workspaceRuntime,
            ).associateBy { it.name }
            tools.filter { it.name !in BACKGROUND_SHELL_TOOLS }.map { plain[it.name] ?: it }
        } else tools
        val childTools = (if (settings.subagent.includeChatHistory) {
            (childBaseTools +
                createConversationReadTool(conversationRepository, assistant.id) +
                createConversationTools(conversationRepository, assistant.id))
                .distinctBy { it.name }
        } else childBaseTools).filter { tool ->
            // 子代理的工具可见性遵循设置，避免开关是摆设
            isSubagentToolAllowed(tool.name, settings.subagent)
        }
        // 父侧用 canReadLocalOutputs 判定；子代理还要额外确认自己真的拿到了能读 /tool_outputs 的工具。
        val childToolsForSubagent =
            if (shouldTruncateChildToolOutput(canReadLocalOutputs, childTools)) {
                childTools.map(::withOutputTruncation)
            } else childTools
        val withSubagent = if (settings.subagent.enabled) {
            tools + createSubagentTools(
                runner = subagentRunner,
                childTools = childToolsForSubagent,
                settings = settings.subagent,
                parentModelId = model.id,
                conversationId = conversationId,
            )
        } else tools
        return if (canReadLocalOutputs) withSubagent.map(::withOutputTruncation) else withSubagent
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspace: WorkspaceEntity?,
        cwd: String?,
        runtime: me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings,
        conversationId: Uuid? = null,
        backgroundShellManager: BackgroundShellManager? = null,
    ): List<Tool> {
        if (workspace == null) return emptyList()
        // 后端先定下来，再按它判断“就绪”和“这个工具在该后端下有没有意义”：
        // SSH 不再因为全局 HTTP 开关而被点亮 job 工具，也不会被整体绕过就绪检查。
        val backend = effectiveExecutionBackend(workspace.isSsh(), runtime)
        if (!workspaceToolsReady(backend, workspace.shellStatus)) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=${workspace.id}, " +
                    "status=${workspace.shellStatus}, backend=$backend"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspace.id, workspaceRepository, cwd, runtime,
            conversationId = conversationId,
            backgroundShellManager = backgroundShellManager,
        ).filter {
            isToolAvailableOnBackend(it.name, backend)
        }
    }
}
