package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceExecutionBackend
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellTask
import me.rerere.rikkahub.data.repository.effectiveExecutionBackend
import me.rerere.rikkahub.data.repository.jobToolsAvailable
import me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceGlobOptions
import me.rerere.workspace.WorkspaceGlobResult
import me.rerere.workspace.WorkspaceGrepOptions
import me.rerere.workspace.WorkspaceGrepOutputMode
import me.rerere.workspace.WorkspaceGrepResult
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceLineSlice
import me.rerere.workspace.WorkspaceLines
import me.rerere.workspace.WorkspaceSearch
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

// fork: 超时完全由 WorkspaceRuntimeSettings 决定 (commandTimeoutSec 默认 30, maxTimeoutSec=0 表示不限),
// 官方 3.1.1 里固定的 600s 上限已被 runtime 配置取代
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/** workspace_read_file 默认每次返回的行数, 与 workspace 模块的 WorkspaceLines.DEFAULT_LIMIT 一致 */
private const val READ_FILE_DEFAULT_LINES = 2000

/** 单次请求的行数上限, 防止一次把几万行塞进上下文 */
private const val READ_FILE_MAX_LINES = 20_000

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    // 只读搜索与找文件, 默认不打断
    "workspace_grep" to false,
    "workspace_glob" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)

/** 直接写文件的工具, 只在支持 [WorkspaceCapabilities.fileWriteTools] 的工作区提供 */
val WorkspaceFileWriteToolNames: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    runtime: WorkspaceRuntimeSettings = WorkspaceRuntimeSettings(),
    conversationId: Uuid? = null,
    backgroundShellManager: BackgroundShellManager? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.let(layout::relativeToWorkspace)

    val capabilities = workspace.capabilities()
    // fork: 后端只有三种。全局 HTTP 远程开关只作用于 LOCAL 工作区；SSH 工作区永远走 SSH 后端，
    // 既没有 fork 的 /job 接口，也不该退化成在本地 proot 里跑远端路径。
    val backend = effectiveExecutionBackend(workspace.isSsh(), runtime)
    val jobsAvailable = jobToolsAvailable(capabilities, backend)
    // 官方 background-shell（预览）：后台命令跑在本地 PRoot 里，按会话归属；
    // fork: 只在实际后端是本地 Rootfs 时提供，HTTP 远程与 SSH 不提供
    val background = backgroundShellManager
        ?.takeIf { capabilities.backgroundShell && backend == WorkspaceExecutionBackend.LOCAL_ROOTFS }
        ?.let { manager -> conversationId?.let { BackgroundShell(manager, it) } }

    return buildList {
        add(createReadFileTool(workspaceId, layout, ::needsApproval, workspaceRepository, backend))
        // 文件都在本机的后端 (LOCAL_ROOTFS / HTTP 远程) 才有本地搜索; SSH 的文件在对端, 这一版只保留 workspace_shell
        if (backend != WorkspaceExecutionBackend.SSH) {
            add(createGrepTool(workspaceId, layout, ::needsApproval, workspaceRepository))
            add(createGlobTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        }
        if (capabilities.fileWriteTools) {
            add(createWriteFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
            add(createEditFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        }
        add(createShellTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd, runtime, backend, background))
        if (background != null) {
            add(createShellOutputTool(background))
            add(createShellKillTool(background))
        }
        if (jobsAvailable) {
            add(createJobStartTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd, runtime, backend))
            add(createJobStatusTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createJobKillTool(workspaceId, ::needsApproval, workspaceRepository))
        }
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    backend: WorkspaceExecutionBackend = WorkspaceExecutionBackend.LOCAL_ROOTFS,
) = Tool(
    name = "workspace_read_file",
    description = buildString {
        if (backend == WorkspaceExecutionBackend.SSH) {
            append("Read a file over SFTP from the assistant's bound SSH workspace host. Paths are absolute on that host; ")
        } else {
            append("Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. ")
        }
        append("Use ${layout.workspaceDir} for the workspace files area. ")
        append("Text files come back with line numbers, $READ_FILE_DEFAULT_LINES lines at a time by default; ")
        append("use offset and limit to page through a large file and workspace_grep to find the interesting lines first. ")
        append("Line numbers are display only: never include them in old_text for workspace_edit_file. ")
        append("Supports UTF-8 text files and images (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).")
    }.replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line number to start reading from. Defaults to 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of lines to return. Defaults to $READ_FILE_DEFAULT_LINES, max $READ_FILE_MAX_LINES.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val offset = params.string("offset")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val limit = params.string("limit")?.trim()?.toIntOrNull()?.coerceIn(1, READ_FILE_MAX_LINES)
                ?: READ_FILE_DEFAULT_LINES
            // SSH 的文本已经在内存里, 直接切片; 本机文件走 workspace 模块的流式按行读
            val slice = if (backend == WorkspaceExecutionBackend.SSH) {
                WorkspaceLines.read(workspaceRepository.readTextInRootfs(workspaceId, path), offset, limit)
            } else {
                workspaceRepository.readLinesInRootfs(workspaceId, path, offset, limit)
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", slice.toNumberedText())
                        put("startLine", slice.startLine)
                        put("endLine", slice.endLine)
                        put("totalLines", slice.totalLines)
                        put("hasMore", slice.hasMore)
                        slice.nextOffset?.let { next -> put("nextOffset", next) }
                        if (slice.clippedLines > 0) put("clippedLines", slice.clippedLines)
                    }.toString()
                )
            )
        }
    },
)

/** 行号只是显示用: `%6d\t内容`, 与 `cat -n` 一致 */
private fun WorkspaceLineSlice.toNumberedText(): String = lines
    .mapIndexed { index, line -> "${(startLine + index).toString().padStart(6)}\t$line" }
    .joinToString("\n")

private const val GREP_DESCRIPTION = "Search file contents under a Rootfs path. Use it to find code instead of " +
    "running grep or cat through workspace_shell. Regex by default, optional literal mode. " +
    "output_mode: files_with_matches (default), content with line numbers, or count. " +
    "Supports glob filtering, context lines and offset/head_limit paging. " +
    "Multiple independent searches can be sent in one reply."

private fun createGrepTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = GREP_DESCRIPTION.replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Regex to search for (or the literal text when literal=true)")
                })
                putSearchPathProperty(layout)
                put("glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Only search files whose path or name matches this glob, e.g. *.kt")
                })
                put("ignore_case", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive match. Defaults to false.")
                })
                put("literal", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat pattern as plain text instead of a regex. Defaults to false.")
                })
                put("output_mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("files_with_matches")
                        add("content")
                        add("count")
                    })
                    put("description", "files_with_matches (default) lists paths, content prints path:line:text, count prints path:count")
                })
                put("context", buildJsonObject {
                    put("type", "integer")
                    put("description", "Context lines before and after each match, 0..20. Only used by output_mode=content.")
                })
                put("head_limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum results to return. Defaults to 100, max 1000.")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Results to skip for paging; pass nextOffset from a previous call.")
                })
                put("include_hidden", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also search dot files and dot directories. Defaults to false. " +
                        "Build and dependency directories are always skipped.")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern")?.takeIf { it.isNotBlank() } ?: error("pattern is required")
        val outputMode = when (val mode = params.string("output_mode")?.trim()?.lowercase()) {
            null, "", "files_with_matches" -> WorkspaceGrepOutputMode.FILES_WITH_MATCHES
            "content" -> WorkspaceGrepOutputMode.CONTENT
            "count" -> WorkspaceGrepOutputMode.COUNT
            else -> error("output_mode must be files_with_matches, content or count, got: $mode")
        }
        val options = WorkspaceGrepOptions(
            pattern = pattern,
            literal = params.booleanValue("literal") ?: false,
            glob = params.string("glob")?.trim()?.takeIf { it.isNotEmpty() },
            ignoreCase = params.booleanValue("ignore_case") ?: false,
            outputMode = outputMode,
            context = params.intValue("context") ?: 0,
            headLimit = params.intValue("head_limit") ?: WorkspaceSearch.DEFAULT_HEAD_LIMIT,
            offset = params.intValue("offset") ?: 0,
            includeHidden = params.booleanValue("include_hidden") ?: false,
        )
        val result = workspaceRepository.grepInRootfs(workspaceId, params.searchPath(layout), options)
        listOf(UIMessagePart.Text(result.toCompactText()))
    },
)

private const val GLOB_DESCRIPTION = "Find files by glob pattern under a Rootfs path, newest first. " +
    "Use it to find files instead of running find or ls through workspace_shell. " +
    "Patterns like *.kt match a file name, deep patterns match the relative path. " +
    "Results are paged with head_limit/offset. Multiple independent searches can be sent in one reply."

private fun createGlobTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = GLOB_DESCRIPTION.replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern, e.g. *.kt or **/*.{ts,tsx}")
                })
                putSearchPathProperty(layout)
                put("head_limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum results to return. Defaults to 100, max 1000.")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Results to skip for paging; pass nextOffset from a previous call.")
                })
                put("include_hidden", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also match dot files and dot directories. Defaults to false. " +
                        "Build and dependency directories are always skipped.")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_glob") },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern")?.takeIf { it.isNotBlank() } ?: error("pattern is required")
        val options = WorkspaceGlobOptions(
            pattern = pattern,
            headLimit = params.intValue("head_limit") ?: WorkspaceSearch.DEFAULT_HEAD_LIMIT,
            offset = params.intValue("offset") ?: 0,
            includeHidden = params.booleanValue("include_hidden") ?: false,
        )
        val result = workspaceRepository.globInRootfs(workspaceId, params.searchPath(layout), options)
        listOf(UIMessagePart.Text(result.toCompactText()))
    },
)

/** 搜索工具的输出是紧凑文本而不是 JSON: 首行给分页信息, 后面每行一条结果 */
private fun WorkspaceGrepResult.toCompactText(): String = buildString {
    append(pagingHeader(total, truncated, nextOffset))
    when (outputMode) {
        WorkspaceGrepOutputMode.FILES_WITH_MATCHES -> files.forEach { append('\n').append(it.path) }
        WorkspaceGrepOutputMode.COUNT -> files.forEach { append('\n').append(it.path).append(':').append(it.matchCount) }
        WorkspaceGrepOutputMode.CONTENT -> lines.forEach { entry ->
            append('\n')
            when {
                entry.isSeparator -> append("--")
                entry.isContext -> append(entry.path).append('-').append(entry.line).append('-').append(entry.text)
                else -> append(entry.path).append(':').append(entry.line).append(':').append(entry.text)
            }
        }
    }
}

private fun WorkspaceGlobResult.toCompactText(): String = buildString {
    append(pagingHeader(total, truncated, nextOffset))
    entries.forEach { entry -> append('\n').append(entry.path).append('\t').append(entry.sizeBytes) }
}

/** 首行: `total=3 truncated=false nextOffset=100` */
private fun pagingHeader(total: Int, truncated: Boolean, nextOffset: Int?): String = buildString {
    append("total=").append(total)
    append(" truncated=").append(truncated)
    nextOffset?.let { append(" nextOffset=").append(it) }
}

private fun kotlinx.serialization.json.JsonObject.booleanValue(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun kotlinx.serialization.json.JsonObject.intValue(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()


private fun createWriteFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun resolveTimeoutMillis(
    requestedSec: Long?,
    runtime: WorkspaceRuntimeSettings,
): Long {
    val maxSec = runtime.maxTimeoutSec.toLong()
    val sec = requestedSec ?: runtime.commandTimeoutSec.toLong()
    val bounded = if (maxSec > 0) {
        if (sec <= 0) maxSec else sec.coerceAtMost(maxSec)
    } else sec.coerceAtLeast(0)
    return bounded.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
}

private class BackgroundShell(val manager: BackgroundShellManager, val conversationId: Uuid)

/** 后台输出等待上限，与官方 workspace_shell_output 一致 */
private const val SHELL_OUTPUT_WAIT_MAX_SECONDS = 600L

private fun createShellTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    runtime: WorkspaceRuntimeSettings = WorkspaceRuntimeSettings(),
    backend: WorkspaceExecutionBackend = WorkspaceExecutionBackend.LOCAL_ROOTFS,
    background: BackgroundShell? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        // 描述必须与仓库里真正执行的后端一致：SSH 不是 Rootfs，也没有 job 工具
        when (backend) {
            WorkspaceExecutionBackend.SSH -> {
                append("Run a shell command on the assistant's bound SSH workspace host. ")
                append("The workspace files area on that host is ${layout.workspaceDir}. ")
            }
            WorkspaceExecutionBackend.REMOTE_HTTP -> {
                append("Run a shell command on the configured REMOTE execution server. ")
                append("/workspace is not mounted there; cwd is relative to the remote workdir. ")
                append("Local file tools are not available in this mode; use shell commands for remote file operations. ")
            }
            WorkspaceExecutionBackend.LOCAL_ROOTFS -> {
                append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at ${layout.workspaceDir}. ")
            }
        }
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Timeout defaults to ${runtime.commandTimeoutSec}s")
        if (runtime.maxTimeoutSec > 0) append(", max ${runtime.maxTimeoutSec}s") else append(", 0 means no limit")
        append(".")
        when (backend) {
            WorkspaceExecutionBackend.SSH -> {
                append(" Long-running commands belong in the SSH terminal; workspace_job_start is not available for SSH workspaces.")
            }
            WorkspaceExecutionBackend.REMOTE_HTTP -> {
                append(" For anything that may outlive the timeout use workspace_job_start instead.")
            }
            WorkspaceExecutionBackend.LOCAL_ROOTFS -> {
                append(" For anything that may outlive the timeout use workspace_job_start instead.")
                append(" Requires Rootfs to be installed and ready.")
                if (background != null) {
                    append(
                        " Set run_in_background for dev servers, watchers or long builds you will check on in this conversation; " +
                            "you are notified when it finishes. Processes left running with `&` are killed when the command returns."
                    )
                }
            }
        }
    },
    parameters = {
        val cwdBase = if (backend == WorkspaceExecutionBackend.SSH) {
            "Working directory relative to the SSH workspace directory (${layout.workspaceDir})."
        } else {
            "Working directory relative to the workspace files root."
        }
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "$cwdBase Defaults to '$defaultCwd'."
                        } else {
                            "$cwdBase Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        buildString {
                            append("Command timeout in seconds. Defaults to ${runtime.commandTimeoutSec}. ")
                            if (runtime.maxTimeoutSec > 0) append("Max ${runtime.maxTimeoutSec}. ") else append("0 means no limit. ")
                            append("The process is killed when the timeout is reached.")
                            if (background != null) append(" Ignored when running in the background.")
                        }
                    )
                })
                if (background != null) {
                    put("run_in_background", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Start the command in the background and return a task id immediately instead of waiting for it. " +
                                "You will be notified with its exit code and the last lines of its output when it finishes, so do not poll it. " +
                                "Use workspace_shell_output to check on it earlier and workspace_shell_kill to stop it. Defaults to false."
                        )
                    })
                }
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        // 官方 3.1.1: cwd 是环境内绝对路径, 由 layout 映射回文件区相对路径
        val cwd = layout.relativeToWorkspace(params.string("cwd") ?: defaultCwd.orEmpty())
        val runInBackground = params.string("run_in_background")?.toBooleanStrictOrNull() ?: false
        val output = if (runInBackground && background != null) {
            background.manager.start(background.conversationId, workspaceId, command, cwd).toJson()
        } else {
            // fork: 超时由 WorkspaceRuntimeSettings 决定 (commandTimeoutSec 默认 30, maxTimeoutSec=0 表示不限)
            val timeoutMillis = resolveTimeoutMillis(params.string("timeout")?.toLongOrNull(), runtime)
            val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
            buildJsonObject {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
                if (result.truncated) put("truncated", true)
            }
        }
        listOf(UIMessagePart.Text(output.toString()))
    },
)

private fun createShellOutputTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_output",
    description = """
        Read the output of a background command started by workspace_shell with run_in_background.
        Returns the combined stdout and stderr produced since the previous read, the command status,
        and the exit code once it has finished. You are notified automatically when a background command
        finishes, so only use this when you need its output before then, such as checking that a server started.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
                put("wait", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Seconds to wait for the command to finish before returning. Defaults to 0 (return immediately), max $SHELL_OUTPUT_WAIT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val params = it.jsonObject
        val taskId = params.string("task_id") ?: error("task_id is required")
        val waitMillis = (params.string("wait")?.toLongOrNull() ?: 0L)
            .coerceIn(0L, SHELL_OUTPUT_WAIT_MAX_SECONDS) * 1_000L
        val result = background.manager.readOutput(background.conversationId, taskId, waitMillis)
        listOf(
            UIMessagePart.Text(
                result.task.toJson {
                    put("output", result.output)
                    if (result.skippedBytes > 0) {
                        put("skippedBytes", result.skippedBytes)
                        put("note", "Earlier output was omitted. The full log is at ${result.task.logPath}.")
                    }
                }.toString()
            )
        )
    },
)

private fun createShellKillTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_kill",
    description = "Stop a background command started by workspace_shell with run_in_background, together with every process it started.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val taskId = it.jsonObject.string("task_id") ?: error("task_id is required")
        val task = background.manager.kill(background.conversationId, taskId)
        listOf(UIMessagePart.Text(task.toJson().toString()))
    },
)

private fun BackgroundShellTask.toJson(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("taskId", id)
    put("status", status.name.lowercase())
    exitCode?.let { put("exitCode", it) }
    put("logPath", logPath)
    extra()
}

private fun createJobStartTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    runtime: WorkspaceRuntimeSettings = WorkspaceRuntimeSettings(),
    backend: WorkspaceExecutionBackend = WorkspaceExecutionBackend.LOCAL_ROOTFS,
) = Tool(
    name = "workspace_job_start",
    description = buildString {
        // 只有 LOCAL 与 HTTP 远程会有这个工具（SSH 由 jobToolsAvailable 挡掉），描述跟着实际后端走
        if (backend == WorkspaceExecutionBackend.REMOTE_HTTP) {
            append("Start a long-running command on the configured REMOTE execution server and return immediately. ")
        } else {
            append("Start a long-running command in the workspace Rootfs and return immediately. ")
        }
        append("Use this for builds, installs and scripts that must not be killed by a tool timeout. ")
        append("Check progress with workspace_job_status and stop it with workspace_job_kill. ")
        append("Output is written to a per-job log; job files are kept for ${runtime.keepJobsDays} days.")
        if (backend == WorkspaceExecutionBackend.REMOTE_HTTP) {
            append(" cwd is relative to the remote workdir; the workspace files area is not mounted there.")
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run in the background")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to the workspace files root")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = layout.relativeToWorkspace(params.string("cwd") ?: defaultCwd.orEmpty())
        val info = workspaceRepository.startJob(workspaceId, command, cwd)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("jobId", info.id)
                    put("pid", info.pid)
                    put("logPath", info.logPath)
                    put("startedAt", info.startedAt)
                }.toString()
            )
        )
    },
)

private fun createJobStatusTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_job_status",
    description = "Show whether a workspace job is still running and return the tail of its log.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("jobId", buildJsonObject {
                    put("type", "string")
                    put("description", "Job id returned by workspace_job_start")
                })
                put("tailLines", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of log lines to return, default 80, max 400")
                })
            },
            required = listOf("jobId"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val jobId = params.string("jobId") ?: error("jobId is required")
        val tailLines = params.string("tailLines")?.toIntOrNull() ?: 80
        val status = workspaceRepository.jobStatus(workspaceId, jobId, tailLines)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("jobId", status.info.id)
                    put("running", status.running)
                    put("command", status.info.command)
                    put("startedAt", status.info.startedAt)
                    put("logTail", status.logTail)
                    if (status.logTruncated) put("logTruncated", true)
                }.toString()
            )
        )
    },
)

private fun createJobKillTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_job_kill",
    description = "Stop a running workspace job and its process group.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("jobId", buildJsonObject {
                    put("type", "string")
                    put("description", "Job id returned by workspace_job_start")
                })
            },
            required = listOf("jobId"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val jobId = params.string("jobId") ?: error("jobId is required")
        val ok = workspaceRepository.killJob(workspaceId, jobId)
        listOf(UIMessagePart.Text(buildJsonObject { put("ok", ok) }.toString()))
    },
)


private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端, 本地工作区由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = absoluteFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use workspace_grep to locate the part you need, then workspace_read_file with offset/limit."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区由工作区后端给出, 本地为工作区文件目录、临时目录和技能目录
private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    layout: WorkspaceLayout,
    name: String,
): Boolean =
    runCatching {
        !layout.isWritable(jsonObject.absolutePath(name))
    }.getOrDefault(true)

/** 搜索工具的 path: 可选, 默认就是工作区文件区 */
private fun JsonObjectBuilder.putSearchPathProperty(layout: WorkspaceLayout) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            "Absolute path inside Rootfs to search. Defaults to ${layout.workspaceDir}; " +
                "bind mounts such as the skills directory work too."
        )
    })
}

/** 搜索路径: 缺省为工作区文件区, 给出了就必须是 Rootfs 内的绝对路径 */
private fun kotlinx.serialization.json.JsonObject.searchPath(layout: WorkspaceLayout): String {
    val raw = string("path")?.replace('\\', '/')?.trim()?.takeIf { it.isNotEmpty() } ?: return layout.workspaceDir
    require(raw.startsWith("/")) { "path must be an absolute path inside Rootfs" }
    require(!raw.contains('\u0000')) { "path contains invalid character" }
    return raw
}

private fun JsonObjectBuilder.putPathProperty(layout: WorkspaceLayout, required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
