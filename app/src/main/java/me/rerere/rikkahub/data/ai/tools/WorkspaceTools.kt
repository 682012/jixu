package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
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
import me.rerere.workspace.WorkspaceLayout
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

// fork: 超时完全由 WorkspaceRuntimeSettings 决定 (commandTimeoutSec 默认 30, maxTimeoutSec=0 表示不限),
// 官方 3.1.1 里固定的 600s 上限已被 runtime 配置取代
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
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
    description = (if (backend == WorkspaceExecutionBackend.SSH) {
        "Read a file over SFTP from the assistant's bound SSH workspace host. Paths are absolute on that host; " +
            "use ${layout.workspaceDir} for the workspace files area. " +
            "Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico)."
    } else {
        "Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. " +
            "Use ${layout.workspaceDir} for the workspace files area. " +
            "Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico)."
    }).replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

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
                                "Read its output with workspace_shell_output and stop it with workspace_shell_kill. Defaults to false."
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
        and the exit code once it has finished. Set wait to block until the command finishes.
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
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
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
