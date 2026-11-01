package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.agent.createToolBatch
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

/** 自己分页、自己控制输出量的工具: 不再经 ToolOutputStore 外置成文件 */
internal val SELF_PAGED_TOOL_NAMES: Set<String> = setOf("workspace_read_file", "workspace_grep", "workspace_glob")

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
                        executionMode = when {
                            tool.readOnlyHint == true && tool.idempotentHint == true -> ToolExecutionMode.PARALLEL_DEDUPLICATED
                            tool.readOnlyHint == true -> ToolExecutionMode.PARALLEL
                            else -> ToolExecutionMode.SERIAL
                        },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // External schemas are discovered lazily only when their catalog becomes large.
        // Native tools have explicit local scheduling metadata; unknown MCP tools remain serial.
        val outputOwner = "assistant:${assistant.id}:conversation:${conversationId ?: "no-conversation"}"
        val outputStore = ToolOutputStore(File(context.filesDir, FileFolders.TOOL_OUTPUTS), outputOwner)
        val baseTools = tools.map(::withBuiltinExecutionMode)
        val childBaseTools = if (settings.subagent.enabled && workspaceTools.any { it.name in BACKGROUND_SHELL_TOOLS }) {
            val plain = createWorkspaceToolsIfReady(
                workspace = workspace,
                cwd = workspaceCwd,
                runtime = settings.workspaceRuntime,
            ).associateBy { it.name }
            baseTools.filter { it.name !in BACKGROUND_SHELL_TOOLS }.map { withBuiltinExecutionMode(plain[it.name] ?: it) }
        } else baseTools
        // Apply child visibility BEFORE proxying: no generic gateway can bypass includeMcp=false.
        val childVisible = (if (settings.subagent.includeChatHistory) {
            (childBaseTools +
                createConversationReadTool(conversationRepository, assistant.id) +
                createConversationTools(conversationRepository, assistant.id))
                .distinctBy { it.name }
        } else childBaseTools).filter { tool -> isSubagentToolAllowed(tool.name, settings.subagent) }
        val childTools = prepareEfficientTools(childVisible.map(::withBuiltinExecutionMode), outputStore)
        val parentTools = if (settings.subagent.enabled) {
            baseTools + createSubagentTools(
                runner = subagentRunner,
                childTools = childTools,
                settings = settings.subagent,
                parentModelId = model.id,
                conversationId = conversationId,
            )
        } else baseTools
        return prepareEfficientTools(parentTools, outputStore)
    }

    private fun prepareEfficientTools(tools: List<Tool>, outputStore: ToolOutputStore): List<Tool> {
        val compact = compactMcpTools(tools)
        val readerName = uniqueToolName(TOOL_OUTPUT_READ_TOOL_NAME, compact.map { it.name }.toSet())
        // 工作区读/搜工具自己分页并控制了输出量, 外置只会让模型多花一轮去读回全文 (同 ToolOutputStore 里 search_web 的豁免)
        val readable = compact.map { tool ->
            if (tool.name in SELF_PAGED_TOOL_NAMES) tool else outputStore.wrap(tool)
        } + outputStore.createReaderTool(readerName)
        // A single structured call can execute an already planned set without extra model round trips.
        return (readable + outputStore.wrap(createToolBatch(readable))).sortedBy { it.name }
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
