package me.rerere.workspace

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 只依赖 JDK 的搜索与按行读取实现。
 *
 * 这里只管"在一个宿主目录里找东西", 不关心 Rootfs 路径映射; 映射与结果翻回 Rootfs 绝对路径
 * 由 [WorkspaceManager] 负责。所有路径都是相对被搜索目录的 `/` 分隔相对路径。
 *
 * 设计取舍:
 * - 走 [Files.walkFileTree], 目录可在 `preVisitDirectory` 时整棵剪掉, 不会先把 node_modules 展开;
 * - 不跟随符号链接 (walkFileTree 默认), 所以链接不会绕出搜索根;
 * - 所有扫描都有文件数 (20000) 与时间 (15s) 预算, 用完标 truncated 而不是继续跑;
 * - content 模式最多收集 20000 行, 避免一个巨大结果集把内存吃光。
 */
class WorkspaceSearch {
    fun grep(start: File, options: WorkspaceGrepOptions): WorkspaceGrepResult {
        require(options.pattern.isNotBlank()) { "pattern is required" }
        val context = options.context.coerceIn(0, MAX_CONTEXT_LINES)
        val headLimit = options.headLimit.coerceIn(1, MAX_HEAD_LIMIT)
        val offset = options.offset.coerceAtLeast(0)
        val regexOptions = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = if (options.literal) {
            Regex(Regex.escape(options.pattern), regexOptions)
        } else {
            Regex(options.pattern, regexOptions)
        }
        val globFilter = options.glob?.takeIf { it.isNotBlank() }?.let(::GlobFilter)
        val base = baseDir(start)
        val budget = ScanBudget(MAX_FILES_SCANNED, SEARCH_TIMEOUT_MS)
        val files = ArrayList<WorkspaceGrepFileMatch>()
        val lines = ArrayList<WorkspaceGrepContentLine>()
        var collected = false

        walk(start, options.includeHidden, budget) { file ->
            val relative = relativePath(base, file)
            if (globFilter != null && start.isFile.not() &&
                !globFilter.matches(relative, file.name)
            ) {
                return@walk true
            }
            if (file.length() > MAX_GREP_FILE_BYTES) return@walk true
            if (isBinary(file)) return@walk true
            val scanned = runCatching { scanFile(file, regex, options.outputMode, context, relative) }
                .getOrNull() ?: return@walk true
            when (options.outputMode) {
                WorkspaceGrepOutputMode.FILES_WITH_MATCHES ->
                    if (scanned.matches > 0) {
                        files += WorkspaceGrepFileMatch(relative, scanned.matches, file.lastModified())
                    }

                WorkspaceGrepOutputMode.COUNT ->
                    if (scanned.matches > 0) {
                        files += WorkspaceGrepFileMatch(relative, scanned.matches, file.lastModified())
                    }

                WorkspaceGrepOutputMode.CONTENT -> {
                    if (scanned.lines.isNotEmpty()) {
                        lines += scanned.lines
                        if (lines.size >= MAX_COLLECTED_LINES) {
                            collected = true
                            return@walk false
                        }
                    }
                }
            }
            true
        }

        val truncatedByCollection = collected || budget.truncated
        return when (options.outputMode) {
            WorkspaceGrepOutputMode.CONTENT -> {
                val sorted = lines.take(MAX_COLLECTED_LINES)
                val page = sorted.drop(offset).take(headLimit)
                val more = offset + page.size < sorted.size
                WorkspaceGrepResult(
                    outputMode = options.outputMode,
                    lines = page,
                    total = sorted.size,
                    truncated = truncatedByCollection || more,
                    nextOffset = if (more) offset + page.size else null,
                    scannedFiles = budget.filesScanned,
                )
            }

            else -> {
                val sorted = files.sortedWith(
                    compareByDescending<WorkspaceGrepFileMatch> { it.updatedAt }.thenBy { it.path }
                )
                val page = sorted.drop(offset).take(headLimit)
                val more = offset + page.size < sorted.size
                WorkspaceGrepResult(
                    outputMode = options.outputMode,
                    files = page,
                    total = sorted.size,
                    truncated = truncatedByCollection || more,
                    nextOffset = if (more) offset + page.size else null,
                    scannedFiles = budget.filesScanned,
                )
            }
        }
    }

    fun glob(start: File, options: WorkspaceGlobOptions): WorkspaceGlobResult {
        require(options.pattern.isNotBlank()) { "pattern is required" }
        val headLimit = options.headLimit.coerceIn(1, MAX_HEAD_LIMIT)
        val offset = options.offset.coerceAtLeast(0)
        val filter = GlobFilter(options.pattern)
        val base = baseDir(start)
        val budget = ScanBudget(MAX_FILES_SCANNED, SEARCH_TIMEOUT_MS)
        val entries = ArrayList<WorkspaceGlobEntry>()

        walk(start, options.includeHidden, budget) { file ->
            val relative = relativePath(base, file)
            if (start.isFile.not() && filter.matches(relative, file.name)) {
                entries += WorkspaceGlobEntry(
                    path = relative,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                )
            }
            true
        }

        val sorted = entries.sortedWith(
            compareByDescending<WorkspaceGlobEntry> { it.updatedAt }.thenBy { it.path }
        )
        val page = sorted.drop(offset).take(headLimit)
        val more = offset + page.size < sorted.size
        return WorkspaceGlobResult(
            entries = page,
            total = sorted.size,
            truncated = budget.truncated || more,
            nextOffset = if (more) offset + page.size else null,
            scannedFiles = budget.filesScanned,
        )
    }

    private class FileScan(
        val matches: Int,
        val lines: List<WorkspaceGrepContentLine> = emptyList(),
    )

    private fun scanFile(
        file: File,
        regex: Regex,
        mode: WorkspaceGrepOutputMode,
        context: Int,
        relative: String,
    ): FileScan {
        if (mode != WorkspaceGrepOutputMode.CONTENT) {
            var matches = 0
            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (regex.containsMatchIn(line)) {
                        matches++
                        if (mode == WorkspaceGrepOutputMode.FILES_WITH_MATCHES) break
                    }
                }
            }
            return FileScan(matches)
        }

        val content = file.readLines(StandardCharsets.UTF_8)
        val matched = ArrayList<Int>()
        content.forEachIndexed { index, line ->
            if (regex.containsMatchIn(line)) matched += index
        }
        if (matched.isEmpty()) return FileScan(0)

        val wanted = sortedSetOf<Int>()
        val hit = HashSet<Int>(matched.size)
        matched.forEach { index ->
            hit += index
            for (i in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(content.lastIndex)) {
                wanted += i
            }
        }

        val out = ArrayList<WorkspaceGrepContentLine>(wanted.size + 4)
        var previous = -1
        for (index in wanted) {
            if (previous >= 0 && index > previous + 1) {
                out += WorkspaceGrepContentLine(path = relative, line = 0, text = "", isSeparator = true)
            }
            out += WorkspaceGrepContentLine(
                path = relative,
                line = index + 1,
                text = clip(content[index]),
                isContext = index !in hit,
            )
            previous = index
        }
        return FileScan(matched.size, out)
    }

    private fun clip(line: String): String =
        if (line.length > MAX_CONTENT_LINE_CHARS) line.take(MAX_CONTENT_LINE_CHARS) + "…" else line

    private fun isBinary(file: File): Boolean {
        val buffer = ByteArray(BINARY_SNIFF_BYTES)
        val read = runCatching { file.inputStream().use { it.read(buffer) } }.getOrElse { return true }
        if (read <= 0) return false
        for (i in 0 until read) {
            if (buffer[i] == 0.toByte()) return true
        }
        return false
    }

    private class ScanBudget(private val maxFiles: Int, timeoutMillis: Long) {
        private val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var filesScanned = 0
            private set
        var truncated = false
            private set

        fun exhausted(): Boolean {
            if (truncated) return true
            if (System.nanoTime() > deadline) {
                truncated = true
                return true
            }
            return false
        }

        /** 记一个文件; 返回 false 表示预算已用完, 调用方应停止扫描 */
        fun takeFile(): Boolean {
            filesScanned++
            if (filesScanned > maxFiles) {
                truncated = true
                return false
            }
            return !exhausted()
        }
    }

    private fun walk(
        start: File,
        includeHidden: Boolean,
        budget: ScanBudget,
        visit: (File) -> Boolean,
    ) {
        if (!start.exists()) return
        if (start.isFile) {
            if (budget.takeFile()) visit(start)
            return
        }
        val startPath = start.toPath()
        Files.walkFileTree(startPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (budget.exhausted()) return FileVisitResult.TERMINATE
                // 起点目录本身不因名字被剪掉, 否则 path 直接指向 node_modules 时就什么都搜不到
                if (dir != startPath) {
                    val name = dir.fileName?.toString().orEmpty()
                    if (name in SKIPPED_DIR_NAMES) return FileVisitResult.SKIP_SUBTREE
                    if (!includeHidden && name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (budget.exhausted()) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val name = file.fileName?.toString().orEmpty()
                if (name.startsWith(".l2s.")) return FileVisitResult.CONTINUE
                if (!includeHidden && name.startsWith(".")) return FileVisitResult.CONTINUE
                if (!budget.takeFile()) return FileVisitResult.TERMINATE
                return if (visit(file.toFile())) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
    }

    companion object {
        /** 默认跳过的目录: 依赖、构建产物、IDE 与缓存目录 */
        val SKIPPED_DIR_NAMES: Set<String> = setOf(
            ".git", ".hg", ".svn", "node_modules", "build", "dist", "out", "target",
            ".gradle", ".idea", "__pycache__", ".venv", "venv", ".cache", ".next", ".nuxt",
        )

        const val DEFAULT_HEAD_LIMIT = 100
        const val MAX_HEAD_LIMIT = 1000
        const val MAX_CONTEXT_LINES = 20
        const val MAX_FILES_SCANNED = 20_000
        const val SEARCH_TIMEOUT_MS = 15_000L
        const val MAX_CONTENT_LINE_CHARS = 500
        const val MAX_COLLECTED_LINES = 20_000

        /** grep 只读不超过这个大小的文件, 更大的按二进制/大文件跳过 */
        const val MAX_GREP_FILE_BYTES = 2L * 1024 * 1024
        const val BINARY_SNIFF_BYTES = 8 * 1024

        /** 被搜索目录; 起点的父目录 (起点是文件时) */
        internal fun baseDir(start: File): File =
            if (start.isFile) start.absoluteFile.parentFile ?: start.absoluteFile else start.absoluteFile

        /** `base` 下的相对路径, 用 `/` 分隔; 起点是单个文件时就是它的文件名 */
        internal fun relativePath(base: File, file: File): String {
            val basePath = base.absoluteFile.toPath().normalize()
            val filePath = file.absoluteFile.toPath().normalize()
            val relative = runCatching { basePath.relativize(filePath) }.getOrNull()
            val text = relative?.joinToString("/") { it.toString() }.orEmpty()
            return text.ifBlank { file.name }
        }
    }
}

// glob 过滤: 同时接受"相对路径"和"文件名"两种匹配写法, 开头的 "**/" 也可以省略
private class GlobFilter(pattern: String) {
    private val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
    private val relaxed = pattern.removePrefix("**/").takeIf { it != pattern }
        ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

    fun matches(relativePath: String, name: String): Boolean {
        val others = listOf(relativePath, name).map { FileSystems.getDefault().getPath(it) }
        if (others.any { matcher.matches(it) }) return true
        return relaxed != null && others.any { relaxed.matches(it) }
    }
}

enum class WorkspaceGrepOutputMode {
    /** 只给命中文件路径 (默认) */
    FILES_WITH_MATCHES,

    /** 给 `路径:行号:文本`, 上下文行用 `路径-行号-文本`, 组间 `--` */
    CONTENT,

    /** 给 `路径:命中行数` */
    COUNT,
}

data class WorkspaceGrepOptions(
    val pattern: String,
    /** 把 pattern 当普通字符串而不是正则 */
    val literal: Boolean = false,
    /** 只搜匹配这个 glob 的文件, 如 `*.kt`, 或匹配所有子目录里的 ts/tsx */
    val glob: String? = null,
    val ignoreCase: Boolean = false,
    val outputMode: WorkspaceGrepOutputMode = WorkspaceGrepOutputMode.FILES_WITH_MATCHES,
    /** 上下文行数, 只在 [WorkspaceGrepOutputMode.CONTENT] 下有意义, 0..20 */
    val context: Int = 0,
    val headLimit: Int = WorkspaceSearch.DEFAULT_HEAD_LIMIT,
    val offset: Int = 0,
    val includeHidden: Boolean = false,
)

data class WorkspaceGrepFileMatch(
    val path: String,
    /** 文件内命中行数; FILES_WITH_MATCHES 模式下只统计到第一处 */
    val matchCount: Int,
    val updatedAt: Long,
)

data class WorkspaceGrepContentLine(
    val path: String,
    val line: Int,
    val text: String,
    /** 上下文行 (不是命中行) */
    val isContext: Boolean = false,
    /** 非连续命中组之间的分隔行 */
    val isSeparator: Boolean = false,
)

data class WorkspaceGrepResult(
    val outputMode: WorkspaceGrepOutputMode,
    val files: List<WorkspaceGrepFileMatch> = emptyList(),
    val lines: List<WorkspaceGrepContentLine> = emptyList(),
    /** 本次结果的完整条数 (分页前) */
    val total: Int = 0,
    /** 触到扫描预算或被 headLimit 截断 */
    val truncated: Boolean = false,
    val nextOffset: Int? = null,
    val scannedFiles: Int = 0,
)

data class WorkspaceGlobOptions(
    val pattern: String,
    val headLimit: Int = WorkspaceSearch.DEFAULT_HEAD_LIMIT,
    val offset: Int = 0,
    val includeHidden: Boolean = false,
)

data class WorkspaceGlobEntry(
    val path: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceGlobResult(
    val entries: List<WorkspaceGlobEntry> = emptyList(),
    val total: Int = 0,
    val truncated: Boolean = false,
    val nextOffset: Int? = null,
    val scannedFiles: Int = 0,
)

internal fun WorkspaceGrepResult.mapPaths(transform: (String) -> String): WorkspaceGrepResult = copy(
    files = files.map { it.copy(path = transform(it.path)) },
    lines = lines.map { if (it.isSeparator) it else it.copy(path = transform(it.path)) },
)

internal fun WorkspaceGlobResult.mapPaths(transform: (String) -> String): WorkspaceGlobResult = copy(
    entries = entries.map { it.copy(path = transform(it.path)) },
)

/**
 * 按行读取: 逐行流式读, 单行超长截断, 总输出到上限就停在整行处。
 *
 * 只依赖 JDK, 本地工作区从文件流式读, SSH 工作区把已经读到的全文交给 [read] 的重载切片。
 */
object WorkspaceLines {
    const val DEFAULT_LIMIT = 2_000
    const val MAX_LINE_CHARS = 2_000
    const val MAX_OUTPUT_CHARS = 40_000

    /** 文件级上限, 与 app 层 workspace_read_file 的 MAX_READ_FILE_BYTES 一致 */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    fun read(
        file: File,
        offset: Int = 1,
        limit: Int = DEFAULT_LIMIT,
        maxFileBytes: Long = MAX_FILE_BYTES,
    ): WorkspaceLineSlice {
        require(offset >= 1) { "offset must be 1 or greater" }
        require(limit >= 1) { "limit must be 1 or greater" }
        require(file.exists()) { "File does not exist: ${file.name}" }
        require(file.isFile) { "Path is not a file: ${file.name}" }
        require(file.length() <= maxFileBytes) {
            "File is too large to read: ${file.length()} bytes (max $maxFileBytes)"
        }
        return file.bufferedReader(StandardCharsets.UTF_8).use { read(it, offset, limit) }
    }

    fun read(
        text: CharSequence,
        offset: Int = 1,
        limit: Int = DEFAULT_LIMIT,
    ): WorkspaceLineSlice = read(StringReader(text.toString()), offset, limit)

    fun read(
        reader: Reader,
        offset: Int = 1,
        limit: Int = DEFAULT_LIMIT,
    ): WorkspaceLineSlice {
        require(offset >= 1) { "offset must be 1 or greater" }
        require(limit >= 1) { "limit must be 1 or greater" }
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader)
        val lines = ArrayList<String>()
        var totalLines = 0
        var chars = 0
        var clippedLines = 0
        var stopped = false
        while (true) {
            val raw = buffered.readLine() ?: break
            totalLines++
            // 输出已经停了, 继续数总行数, 好让调用方知道还有多少
            if (stopped) continue
            if (totalLines < offset) continue
            if (lines.size >= limit) {
                stopped = true
                continue
            }
            val text = if (raw.length > MAX_LINE_CHARS) {
                clippedLines++
                raw.substring(0, MAX_LINE_CHARS)
            } else {
                raw
            }
            // 到字符上限就停在整行处; 第一行无论多长都给出
            if (lines.isNotEmpty() && chars + text.length + 1 > MAX_OUTPUT_CHARS) {
                stopped = true
                continue
            }
            lines += text
            chars += text.length + 1
        }
        val startLine = offset
        val endLine = offset + lines.size - 1
        val hasMore = lines.isNotEmpty() && endLine < totalLines
        return WorkspaceLineSlice(
            lines = lines,
            startLine = startLine,
            endLine = endLine,
            totalLines = totalLines,
            hasMore = hasMore,
            nextOffset = if (hasMore) endLine + 1 else null,
            clippedLines = clippedLines,
        )
    }
}

data class WorkspaceLineSlice(
    val lines: List<String>,
    /** 请求的起始行 (1 起) */
    val startLine: Int,
    /** 实际给出的最后一行; 没有内容时等于 [startLine] - 1 */
    val endLine: Int,
    val totalLines: Int,
    val hasMore: Boolean,
    val nextOffset: Int?,
    /** 因为超过单行长度上限被截断的行数 */
    val clippedLines: Int = 0,
)
