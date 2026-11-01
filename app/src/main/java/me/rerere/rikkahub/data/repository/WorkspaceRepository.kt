package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.service.WorkspaceJobForegroundService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceBackgroundCommand
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceGlobOptions
import me.rerere.workspace.WorkspaceGlobResult
import me.rerere.workspace.WorkspaceGrepOptions
import me.rerere.workspace.WorkspaceGrepResult
import me.rerere.workspace.WorkspaceJobInfo
import me.rerere.workspace.WorkspaceJobManager
import me.rerere.workspace.WorkspaceJobStatus
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceLineSlice
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshShell
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    // fork: job 前台服务与远程执行需要 Application Context
    private val context: android.content.Context,
    // 官方 3.1.1: SSH 远程工作区连接池
    private val sshPool: SshConnectionPool,
) {
    private val jobManager = WorkspaceJobManager(manager)
    private val workspaceFilesExporter = WorkspaceFilesExporter()
    private val workspaceFilesImporter = WorkspaceFilesImporter()
    private val remoteClient = me.rerere.rikkahub.data.repository.RemoteExecutionClient()
    // 这个协程只负责"任务结束后释放前台服务"。launch 里逃出来的异常没人接就是进程级崩溃
    // (job.json 被清理或损坏时 status 会抛 "Job not found"), 这里只记日志;
    // 服务释放写在 finally 里, 监视失败也不会漏掉 release。
    private val jobMonitorScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Workspace job monitor failed", e)
        }
    )

    private fun appContext(): android.content.Context = context.applicationContext

    private fun remoteCwd(workdir: String, cwd: String): String =
        listOf(workdir.trimEnd('/'), cwd.trimStart('/')).filter { it.isNotBlank() }.joinToString("/")

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        val base = manager.workspaceDir("import-cleanup-probe").absoluteFile.parentFile
        if (base != null) runCatching { WorkspaceImportStaging.cleanupOnce(base) }
            .onFailure { Log.w(TAG, "Import staging cleanup failed", it) }
        for (workspace in workspaces) {
            // 文件不在本机的工作区 (如远程) 没有本地目录和 Rootfs
            if (!workspace.capabilities().localFiles) continue
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name) &&
                !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    /** 用一条临时连接测试 SSH 配置; [id] 不为空时, 表单里留空的凭据沿用该工作区已保存的值 */
    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        return runInterruptible(Dispatchers.IO) {
            sshPool.probe(draft.toSettings(previous).toConnectionConfig())
        }
    }

    /** 新建 ([id] 为 null) 或更新 SSH 工作区 */
    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
        val existing = id?.let { dao.getById(it) ?: error("Workspace not found: $it") }
        require(existing == null || existing.isSsh()) { "Not an SSH workspace: $id" }
        val finalName = draft.name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        val settings = withContext(Dispatchers.IO) { draft.toSettings(existing?.sshSettings()) }
        val now = System.currentTimeMillis()
        val workspace = existing?.copy(
            name = finalName,
            sshConfig = JsonInstant.encodeToString(settings),
            updatedAt = now,
        ) ?: Uuid.random().toString().let { newId ->
            WorkspaceEntity(
                id = newId,
                name = finalName,
                root = newId,
                // 远程工作区不需要安装 Rootfs, 视为 shell 已就绪
                shellStatus = WorkspaceShellStatus.READY.name,
                createdAt = now,
                updatedAt = now,
                type = WorkspaceType.SSH.name,
                sshConfig = JsonInstant.encodeToString(settings),
            )
        }
        dao.upsert(workspace)
        withContext(Dispatchers.IO) { sshPool.close(workspace.id) }
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        require(workspace.capabilities().rootfs) { "Rootfs is not available for ${workspace.type} workspaces" }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    /** 工作区在执行环境内的目录约定, 工作区不存在时返回 null */
    suspend fun layout(id: String): WorkspaceLayout? = dao.getById(id)?.let { workspace ->
        // 只需要目录约定, 不解密凭据也不建立连接
        if (workspace.isSsh()) {
            workspace.sshSettings()?.let { SshWorkspaceBackend.layoutOf(it.remoteDir) }
        } else {
            manager.layout
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> {
        val workspace = dao.getById(id) ?: return emptyList()
        return runInterruptible(Dispatchers.IO) { backendOf(workspace).listFiles(area, path) }
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = readFromBackend(id) { it.readText(path) }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withBackend(id) { it.writeText(path, text, overwrite) }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceBackend.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = readFromBackend(id) { backend ->
        when (area) {
            WorkspaceStorageArea.FILES -> backend.readText(path)
            WorkspaceStorageArea.LINUX -> {
                val size = backend.fileSize(area, path)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 ($size bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    backend.exportFile(area, path, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withBackend(id) { it.importFile(area, destinationPath, fileName, inputStream) }

    /** 宿主机上可直接访问的文件; 没有本地落点的工作区返回 null */
    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): File? = withBackend(id) { it.localFile(area, path) }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportFile(area, path, outputStream) }

    /** 按环境内绝对路径读取文件大小, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun absoluteFileSize(
        id: String,
        path: String,
    ): Long = readFromBackend(id) { it.absoluteFileSize(path) }

    /** 按环境内绝对路径解析宿主机上的文件, 不存在、不是文件或没有本地落点时返回 null */
    suspend fun resolveAbsoluteFile(
        id: String,
        path: String,
    ): File? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        runCatching { backendOf(workspace).localAbsoluteFile(path) }
            .getOrNull()
            ?.takeIf { it.isFile }
    }

    /** 执行命令实际使用的 shell 路径 */
    suspend fun shell(id: String): String = readFromBackend(id) { it.shell() }

    /** 按环境内绝对路径写入文本; 本地工作区在 Rootfs 内执行 shell 写入, 因此和命令一样支持取消 */
    suspend fun writeAbsoluteText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).writeAbsoluteText(path, text, overwrite)
        }
    }

    /** 按环境内绝对路径导出文件内容, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportAbsoluteFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportAbsoluteFile(path, outputStream) }

    /**
     * 在 Rootfs 内任意绝对路径上搜索文本。
     *
     * LOCAL_ROOTFS 与 HTTP 远程执行都走 workspace 模块的本地实现: HTTP 远程只是命令在服务器上跑,
     * 工作区文件仍在本机。SSH 工作区的文件在对端, 这一版不提供搜索, 用 workspace_shell。
     */
    suspend fun grepInRootfs(
        id: String,
        path: String,
        options: WorkspaceGrepOptions,
    ): WorkspaceGrepResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(!workspace.isSsh()) { SSH_ROOTFS_SEARCH_UNSUPPORTED_MESSAGE }
        return runInterruptible(Dispatchers.IO) { manager.grepRootfs(workspace.root, path, options) }
    }

    /** 在 Rootfs 内任意绝对路径上按 glob 找文件, 后端限制同上 */
    suspend fun globInRootfs(
        id: String,
        path: String,
        options: WorkspaceGlobOptions,
    ): WorkspaceGlobResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(!workspace.isSsh()) { SSH_ROOTFS_SEARCH_UNSUPPORTED_MESSAGE }
        return runInterruptible(Dispatchers.IO) { manager.globRootfs(workspace.root, path, options) }
    }

    /** 按行读取 Rootfs 内文件, offset 从 1 起; 流式读, 不受整文件大小限制之外的额外约束 */
    suspend fun readLinesInRootfs(
        id: String,
        path: String,
        offset: Int,
        limit: Int,
    ): WorkspaceLineSlice {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(!workspace.isSsh()) { SSH_ROOTFS_SEARCH_UNSUPPORTED_MESSAGE }
        return runInterruptible(Dispatchers.IO) {
            manager.readRootfsLines(workspace.root, path, offset, limit)
        }
    }

    /**
     * 把工作区的 /workspace 目录打成 zip，跳过可重建的大目录。
     *
     * fork.35 修复（实现搬到 [WorkspaceFilesExporter]，纯 JVM、可单测）：
     * - 区分目录剪枝（node_modules/.git/build/.jobs 等整棵跳过），不再先展开成 File 列表；
     * - 不跟随符号链接，链接/特殊文件跳过并计数，越界与成环都不会进 zip；
     * - 扫描阶段与拷贝的每块缓冲区都可取消；
     * - 读取/写入失败向上抛 IOException，不会生成“不完整但显示成功”的备份。
     */
    suspend fun exportWorkspaceFiles(
        id: String,
        outputStream: OutputStream,
        skipRebuildable: Boolean = true,
        onProgress: (done: Long, total: Long, current: String) -> Unit = { _, _, _ -> },
    ): WorkspaceFilesExporter.Summary = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // 文件不在本机的工作区 (如 SSH) 导出必然失败, 而且导出入口先以 "wt" 打开目标文件,
        // 失败后用户只剩一个被截成 0 字节的文件, 所以这里先拒绝。
        require(workspace.capabilities().localFiles) {
            "Workspace files are not on this device: ${workspace.name}"
        }
        val root = manager.filesDir(workspace.root)
        require(root.isDirectory) { "Workspace files directory missing" }
        val exporter = if (skipRebuildable) workspaceFilesExporter
            else WorkspaceFilesExporter(skippedDirNames = setOf(".jobs"))
        val summary = exporter.export(root, outputStream, onProgress)
        if (summary.skippedTotal > 0 || summary.prunedDirCount > 0) {
            Log.w(
                TAG,
                "export ${workspace.root}: skipped ${summary.skippedSymlinks} symlink(s) and " +
                    "${summary.skippedSpecialFiles} special file(s)",
            )
        }
        summary
    }

    /**
     * 把一个 ZIP 的文件内容导入为全新的本地工作区，不覆盖旧工作区。
     *
     * 语义（安全优先，绝不覆盖任何现有数据）：
     * - 目标是新建 workspace：新 id、新目录 `workspaces/<id>`。当前工作区、其它工作区、数据库里的旧记录、
     *   设置、LinuxRootfs 全程不参与，不会被写入或删除；
     * - 解包在 `workspaces/.import-<id>` 里完成，全部校验通过后才 rename 成正式目录，最后才写 DAO。
     *   任一步失败（含协程取消、DB 写失败）都只删除本次的临时目录，不会留下"有记录没文件"的幽灵工作区；
     * - ZIP 里只有 `files/`（即 /workspace 文件区）。**包内不含 LinuxRootfs**，导入后的工作区 shell 状态是
     *   DISABLED，需要时按现有流程再安装 rootfs；
     * - fork 旧版 [exportWorkspaceFiles] 产出的裸 ZIP 可以直接导入；旧包没有 Unix 权限位、也不含符号链接，
     *   导入后文件统一 0644（可执行位丢失），[WorkspaceFilesImporter.Summary.permissionsAreGuessed] 会为 true；
     * - [stripWrapper] 非空时显式剥掉一层外层包装目录（如 `workspace/`、`files/`），不做自动猜测。
     *
     * 阻塞读输入流，必须在 IO 线程调用（内部已切 Dispatchers.IO）。[input] 由本方法负责关闭。
     *
     * @return 新建工作区及导入统计；[onCommitted] 在注册成功后调用，即使此时取消也保留结果。
     */
    suspend fun importWorkspaceFiles(
        name: String,
        input: InputStream,
        stripWrapper: String? = null,
        onCommitted: (WorkspaceImportResult) -> Unit = {},
        onProgress: (bytes: Long, files: Int, current: String) -> Unit = { _, _, _ -> },
    ): WorkspaceImportResult = withContext(Dispatchers.IO) {
        try {
            val finalName = name.trim().ifBlank { "Workspace" }
            require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
            }
            val id = Uuid.random().toString()
            val now = System.currentTimeMillis()
            val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = now,
            )
            val finalDir = manager.workspaceDir(workspace.root)
            // 临时目录放在 workspace 根目录下、用新 id 命名：与目标目录同文件系统，发布只需要 rename；
            // 目录名只含 [A-Za-z0-9._-]，与 WorkspaceManager 的 root 命名规则兼容。
            val baseDir = finalDir.absoluteFile.parentFile
            ?: error("Workspace base directory is unavailable: $finalDir")
            val stagingRoot = File(baseDir, ".import-${workspace.root}")
            runCatching { WorkspaceImportStaging.cleanupOnce(baseDir) }
            .onFailure { Log.w(TAG, "Import staging cleanup failed", it) }
            val summary = WorkspaceImportStaging.withStagingLease(stagingRoot) {
                workspaceFilesImporter.importWorkspace(
                source = input,
                stagingRoot = stagingRoot,
                finalDir = finalDir,
                stripWrapper = stripWrapper,
                onProgress = onProgress,
                register = { summary ->
                    manager.ensureWorkspace(workspace.root)
                    dao.upsert(workspace)
                    // Tell the UI about the commit even if cancellation wins on returning to Main.
                    // A UI observer must not roll back a successfully registered workspace.
                    runCatching { onCommitted(WorkspaceImportResult(workspace, summary)) }
                        .onFailure { Log.w(TAG, "Import commit observer failed", it) }
                },
                )
            }
            Log.i(
                TAG,
                "importWorkspaceFiles ${workspace.id}: ${summary.fileCount} file(s), " +
                    "${summary.directoryCount} dir(s), ${summary.byteCount} byte(s), " +
                    "exec=${summary.executableFiles}, noUnixMode=${summary.entriesWithoutUnixMode}, " +
                    "wrapper=${summary.strippedWrapper}",
            )
            WorkspaceImportResult(workspace, summary)
        } finally {
            runCatching { input.close() }
        }
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        backendOf(workspace).deleteFile(area, path, recursive)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        allowRemote: Boolean = true,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // fork: 远程后端优先。长任务交给服务器，避免 Android 后台限制。
        // 全局 HTTP 开关只对 LOCAL 工作区生效；SSH 工作区永远走 SSH 后端，命令与文件都在同一台机器。
        val remote = effectiveRemoteExecution(
            isSsh = workspace.isSsh(),
            runtime = settingsStore.awaitLoaded().workspaceRuntime,
        )
        if (allowRemote && remote != null) {
            require(remote.baseUrl.isNotBlank()) { "Remote base URL is empty" }
            require(stdin == null) { "Remote shell does not support stdin" }
            val remoteCwd = listOf(remote.workdir.trimEnd('/'), cwd.trimStart('/'))
                .filter { it.isNotBlank() }
                .joinToString("/")
            return remoteClient.execute(remote, command, remoteCwd, timeoutMillis)
        }
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).executeCommand(command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun startJob(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = 0L,
    ): WorkspaceJobInfo = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val runtime = settingsStore.awaitLoaded().workspaceRuntime
        // fork: 全局 HTTP 开关只对 LOCAL 生效。SSH 没有 job 通道，也不能退回到本地 proot 里跑远端路径。
        val remote = effectiveRemoteExecution(isSsh = workspace.isSsh(), runtime = runtime)
        if (remote != null) {
            require(remote.baseUrl.isNotBlank()) { "Remote base URL is empty" }
            return@withContext remoteClient.startJob(remote, command, remoteCwd(remote.workdir, cwd))
        }
        check(!workspace.isSsh()) { SSH_JOB_UNSUPPORTED_MESSAGE }
        manager.ensureWorkspace(workspace.root)
        jobManager.prune(workspace.root, runtime.keepJobsDays)
        // Start the foreground service before starting work. A denied FGS must not leave an untracked task.
        val serviceId = Uuid.random().toString()
        check(WorkspaceJobForegroundService.acquire(appContext(), serviceId)) {
            "Cannot start foreground service; bring the app to the foreground and try again"
        }
        try {
            val info = jobManager.start(workspace.root, command, cwd, timeoutMillis, workspace.shellCompatibilityMode)
            jobMonitorScope.launch {
                try {
                    while (jobManager.status(workspace.root, info.id).running) delay(2_000)
                } finally {
                    WorkspaceJobForegroundService.release(appContext(), serviceId)
                }
            }
            info
        } catch (e: Exception) {
            WorkspaceJobForegroundService.release(appContext(), serviceId)
            throw e
        }
    }

    suspend fun jobStatus(
        id: String,
        jobId: String,
        tailLines: Int = 80,
    ): WorkspaceJobStatus = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val remote = effectiveRemoteExecution(
            isSsh = workspace.isSsh(),
            runtime = settingsStore.awaitLoaded().workspaceRuntime,
        )
        if (remote != null) {
            require(remote.baseUrl.isNotBlank()) { "Remote base URL is empty" }
            return@withContext remoteClient.jobStatus(remote, jobId, tailLines)
        }
        check(!workspace.isSsh()) { SSH_JOB_UNSUPPORTED_MESSAGE }
        jobManager.status(workspace.root, jobId, tailLines)
    }

    suspend fun killJob(id: String, jobId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val remote = effectiveRemoteExecution(
            isSsh = workspace.isSsh(),
            runtime = settingsStore.awaitLoaded().workspaceRuntime,
        )
        if (remote != null) {
            require(remote.baseUrl.isNotBlank()) { "Remote base URL is empty" }
            return@withContext remoteClient.killJob(remote, jobId)
        }
        check(!workspace.isSsh()) { SSH_JOB_UNSUPPORTED_MESSAGE }
        jobManager.kill(workspace.root, jobId)
    }

    suspend fun listJobs(id: String): List<WorkspaceJobInfo> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        val backend = effectiveExecutionBackend(workspace.isSsh(), settingsStore.awaitLoaded().workspaceRuntime)
        when (val verdict = assertLocalJobListing(backend)) {
            JobListingVerdict.Allowed -> jobManager.list(workspace.root)
            is JobListingVerdict.Unsupported -> error(verdict.message)
            JobListingVerdict.NoWorkspace -> emptyList()
        }
    }

    /** 在后台启动命令并立即返回, 进程的生命周期由调用方管理 */
    suspend fun startBackgroundCommand(
        id: String,
        command: String,
        cwd: String,
        taskId: String,
    ): WorkspaceBackgroundCommand = withBackend(id) { it.startBackgroundCommand(command, cwd, taskId) }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            sshPool.close(id)
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    /** 打开 SSH 工作区的交互式 shell, 阻塞到连接和 shell 都建立好, 不能在主线程调用 */
    fun openSshShell(workspace: WorkspaceEntity, columns: Int, rows: Int): SshShell {
        val backend = backendOf(workspace) as? SshWorkspaceBackend
            ?: error("Not an SSH workspace: ${workspace.id}")
        return backend.openShell(columns, rows)
    }

    private fun backendOf(workspace: WorkspaceEntity): WorkspaceBackend =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshWorkspaceBackend(sshPool, workspace.id, settings.toConnectionConfig())
        } else {
            manager.backend(workspace.root, workspace.shellCompatibilityMode)
        }

    private fun SshWorkspaceSettings.toConnectionConfig() = SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = encryptedPassword?.let(::decryptSecret),
        privateKey = encryptedPrivateKey?.let(::decryptSecret),
        passphrase = encryptedPassphrase?.let(::decryptSecret),
        remoteDir = remoteDir,
        hostKey = hostKey,
    )

    /**
     * 表单转成持久化配置并加密凭据。编辑时留空的凭据沿用 [previous] 的密文;
     * 私钥口令跟随私钥: 换了新私钥就以表单为准, 沿用旧私钥时口令留空也沿用。
     */
    private fun SshWorkspaceDraft.toSettings(previous: SshWorkspaceSettings?): SshWorkspaceSettings {
        val remoteDir = remoteDir.trim().trimEnd('/').ifEmpty { "/" }
        require(remoteDir.startsWith("/")) { "Remote directory must be an absolute path" }
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
        require(port in 1..65535) { "Invalid port: $port" }
        val keepPrivateKey = privateKey.isBlank()
        return SshWorkspaceSettings(
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            remoteDir = remoteDir,
            hostKey = hostKey,
            encryptedPassword = if (authType == SshAuthType.PASSWORD) {
                password.takeIf { it.isNotEmpty() }?.let(SecretCipher::encrypt) ?: previous?.encryptedPassword
            } else {
                null
            },
            encryptedPrivateKey = if (authType == SshAuthType.PRIVATE_KEY) {
                privateKey.takeIf { it.isNotBlank() }?.let { SecretCipher.encrypt(it.trim()) }
                    ?: previous?.encryptedPrivateKey
            } else {
                null
            },
            encryptedPassphrase = when {
                authType != SshAuthType.PRIVATE_KEY -> null
                passphrase.isNotEmpty() -> SecretCipher.encrypt(passphrase)
                keepPrivateKey -> previous?.encryptedPassphrase
                else -> null
            },
        )
    }

    private fun decryptSecret(value: String): String = try {
        SecretCipher.decrypt(value)
    } catch (e: Exception) {
        throw IOException(
            "Saved SSH credentials cannot be decrypted (for example after restoring a backup on another device). " +
                "Re-enter them in the workspace settings.",
            e,
        )
    }

    /**
     * 读操作: 取消时中断线程, 远程读取卡在网络上时也能及时返回。
     * 中途放弃没有副作用; 写操作被打断会留下写了一半的文件, 走 [withBackend] 照常执行完。
     */
    private suspend fun <T> readFromBackend(id: String, block: (WorkspaceBackend) -> T): T {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return try {
            runInterruptible(Dispatchers.IO) { block(backendOf(workspace)) }
        } catch (e: Exception) {
            // 被中断的读取会抛出 JSch 包装过的 IO 异常, 已取消时统一按取消处理, 免得被当成连接失败
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private suspend fun <T> withBackend(id: String, block: (WorkspaceBackend) -> T): T =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            block(backendOf(workspace))
        }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        /** SSH 工作区的文件在对端, 本地搜索没有意义 */
        internal const val SSH_ROOTFS_SEARCH_UNSUPPORTED_MESSAGE =
            "Searching is not supported for SSH workspaces; use workspace_shell (grep/find) instead"
    }
}

/** Result and recovery receipt for the newly registered file-only workspace. */
data class WorkspaceImportResult(val workspace: WorkspaceEntity, val summary: WorkspaceFilesImporter.Summary)
