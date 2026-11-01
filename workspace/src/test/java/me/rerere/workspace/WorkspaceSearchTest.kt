package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 搜索与按行读取的纯逻辑测试: grep 三种 output_mode、上下文、分页、跳过目录、隐藏文件、
 * 二进制/大文件跳过、长行截断、越界拒绝、glob 排序、readLines 的分页与截断。
 */
class WorkspaceSearchTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var skills: File
    private lateinit var manager: WorkspaceManager
    private val search = WorkspaceSearch()

    private val root = "search-test"

    @Before
    fun setUp() {
        skills = tempFolder.newFolder("skills")
        manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(WorkspaceBindMount(source = skills, target = "/skills")),
        ).also { it.ensureWorkspace(root) }
        workspace = manager.filesDir(root)
    }

    private fun write(relative: String, text: String): File =
        File(workspace, relative).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    // --- grep: output_mode ---------------------------------------------------

    @Test
    fun `grep files with matches lists each file once`() {
        write("a.kt", "fun a() = 1\nfun b() = 2\n")
        write("nested/b.kt", "val x = 1\n")
        write("c.md", "nothing here\n")

        val result = manager.grepRootfs(root, "/workspace", WorkspaceGrepOptions(pattern = "fun "))

        assertEquals(WorkspaceGrepOutputMode.FILES_WITH_MATCHES, result.outputMode)
        assertEquals(listOf("/workspace/a.kt"), result.files.map { it.path })
        assertEquals(1, result.total)
        assertFalse(result.truncated)
        assertNull(result.nextOffset)
    }

    @Test
    fun `grep content returns path line text and supports regex`() {
        write("a.kt", "first\nfun main() {}\nlast\n")

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "fun \\w+", outputMode = WorkspaceGrepOutputMode.CONTENT),
        )

        assertEquals(1, result.lines.size)
        val line = result.lines.single()
        assertEquals("/workspace/a.kt", line.path)
        assertEquals(2, line.line)
        assertEquals("fun main() {}", line.text)
        assertFalse(line.isContext)
    }

    @Test
    fun `grep literal mode does not treat pattern as regex`() {
        write("a.kt", "a.b\naxb\n")

        val literal = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "a.b", literal = true, outputMode = WorkspaceGrepOutputMode.CONTENT),
        )
        val regex = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "a.b", outputMode = WorkspaceGrepOutputMode.CONTENT),
        )

        assertEquals(listOf("a.b"), literal.lines.map { it.text })
        assertEquals(listOf("a.b", "axb"), regex.lines.map { it.text })
    }

    @Test
    fun `grep count reports per file match counts sorted by mtime`() {
        val older = write("older.kt", "hit\nhit\n")
        val newer = write("newer.kt", "hit\n")
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "hit", outputMode = WorkspaceGrepOutputMode.COUNT),
        )

        assertEquals(listOf("/workspace/newer.kt", "/workspace/older.kt"), result.files.map { it.path })
        assertEquals(listOf(1, 2), result.files.map { it.matchCount })
        assertEquals(2, result.total)
    }

    // --- grep: 参数 ----------------------------------------------------------

    @Test
    fun `grep ignore case is off by default`() {
        write("a.kt", "Needle\n")

        val strict = manager.grepRootfs(root, "/workspace", WorkspaceGrepOptions(pattern = "needle"))
        val relaxed = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", ignoreCase = true),
        )

        assertEquals(0, strict.total)
        assertEquals(1, relaxed.total)
    }

    @Test
    fun `grep context adds surrounding lines and separator between groups`() {
        write("a.kt", "one\ntwo\nmatch\nfour\nfive\nsix\nmatch\neight\n")

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(
                pattern = "match",
                outputMode = WorkspaceGrepOutputMode.CONTENT,
                context = 1,
            ),
        )

        val rendered = result.lines.map { entry ->
            when {
                entry.isSeparator -> "--"
                entry.isContext -> "${entry.line}-"
                else -> "${entry.line}+"
            }
        }
        assertEquals(listOf("2-", "3+", "4-", "--", "6-", "7+", "8-"), rendered)
    }

    @Test
    fun `grep context is clamped to at most twenty lines`() {
        write("a.kt", (1..40).joinToString("\n") { "line$it" } + "\nlineHit\n")

        val wide = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "lineHit", outputMode = WorkspaceGrepOutputMode.CONTENT, context = 100),
        )

        // 命中行 (第 41 行) + 前面 20 行, 文件到此为止没有后文
        assertEquals(21, wide.lines.size)
    }

    @Test
    fun `grep glob filters by file name`() {
        write("a.kt", "needle\n")
        write("a.ts", "needle\n")

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", glob = "*.kt"),
        )

        assertEquals(listOf("/workspace/a.kt"), result.files.map { it.path })
    }

    @Test
    fun `grep glob with brace alternation matches both extensions`() {
        write("a.ts", "needle\n")
        write("deep/b.tsx", "needle\n")
        write("deep/c.md", "needle\n")

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", glob = "**/*.{ts,tsx}"),
        )

        assertEquals(
            listOf("/workspace/a.ts", "/workspace/deep/b.tsx"),
            result.files.map { it.path }.sorted(),
        )
    }

    @Test
    fun `grep head limit and offset page through files`() {
        repeat(5) { index -> write("f$index.kt", "needle\n") }

        val first = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", headLimit = 2),
        )
        val second = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", headLimit = 2, offset = 2),
        )

        assertEquals(5, first.total)
        assertEquals(2, first.files.size)
        assertEquals(2, first.nextOffset)
        assertTrue(first.truncated)
        assertEquals(2, second.files.size)
        assertEquals(4, second.nextOffset)
        assertEquals(4, (first.files + second.files).map { it.path }.distinct().size)
    }

    @Test
    fun `grep head limit is clamped to a thousand`() {
        repeat(3) { index -> write("f$index.kt", "needle\n") }

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", headLimit = 5000),
        )

        assertEquals(3, result.files.size)
        assertNull(result.nextOffset)
    }

    // --- grep: 跳过规则 -------------------------------------------------------

    @Test
    fun `grep skips build directories and hidden entries by default`() {
        write("src/a.kt", "needle\n")
        write("node_modules/pkg/b.kt", "needle\n")
        write(".git/config.kt", "needle\n")
        write("build/c.kt", "needle\n")
        write(".secret.kt", "needle\n")

        val result = manager.grepRootfs(root, "/workspace", WorkspaceGrepOptions(pattern = "needle"))

        assertEquals(listOf("/workspace/src/a.kt"), result.files.map { it.path })
    }

    @Test
    fun `grep include hidden finds dot files but still skips build folders`() {
        write("src/a.kt", "needle\n")
        write(".hidden/d.kt", "needle\n")
        write(".secret.kt", "needle\n")
        write("node_modules/e.kt", "needle\n")

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", includeHidden = true),
        )

        assertEquals(
            listOf("/workspace/.hidden/d.kt", "/workspace/.secret.kt", "/workspace/src/a.kt"),
            result.files.map { it.path }.sorted(),
        )
    }

    @Test
    fun `grep inside a skipped directory still searches it`() {
        write("node_modules/pkg/b.kt", "needle\n")

        val result = manager.grepRootfs(
            root,
            "/workspace/node_modules",
            WorkspaceGrepOptions(pattern = "needle"),
        )

        assertEquals(listOf("/workspace/node_modules/pkg/b.kt"), result.files.map { it.path })
    }

    @Test
    fun `grep skips binary files and files over two megabytes`() {
        write("text.kt", "needle\n")
        File(workspace, "blob.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x4E, 0x00, 0x45, 0x45, 0x44, 0x4C, 0x45))
        }
        File(workspace, "huge.log").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray((2L * 1024 * 1024).toInt() + 1) { 'n'.code.toByte() })
        }

        val result = manager.grepRootfs(root, "/workspace", WorkspaceGrepOptions(pattern = "needle|nnn"))

        assertEquals(listOf("/workspace/text.kt"), result.files.map { it.path })
    }

    @Test
    fun `grep clips very long lines and marks them`() {
        val long = "x".repeat(600)
        write("long.kt", "$long needle\n")

        val result = manager.grepRootfs(
            root,
            "/workspace",
            WorkspaceGrepOptions(pattern = "needle", outputMode = WorkspaceGrepOutputMode.CONTENT),
        )

        val line = result.lines.single()
        assertEquals(501, line.text.length)
        assertTrue(line.text.endsWith("…"))
    }

    // --- 路径映射与越界 -------------------------------------------------------

    @Test
    fun `grep maps bind mount results back to rootfs paths`() {
        File(skills, "issue/SKILL.md").apply {
            parentFile?.mkdirs()
            writeText("---\nname: skill\n---\n")
        }

        val result = manager.grepRootfs(root, "/skills", WorkspaceGrepOptions(pattern = "name:"))

        assertEquals(listOf("/skills/issue/SKILL.md"), result.files.map { it.path })
    }

    @Test
    fun `grep refuses kernel filesystems`() {
        val error = assertThrows(IllegalStateException::class.java) {
            manager.grepRootfs(root, "/proc", WorkspaceGrepOptions(pattern = "x"))
        }
        assertTrue(error.message.orEmpty().contains("kernel filesystem"))
    }

    @Test
    fun `grep on a single file path searches just that file`() {
        write("a.kt", "needle\n")
        write("b.kt", "nothing\n")

        val result = manager.grepRootfs(root, "/workspace/a.kt", WorkspaceGrepOptions(pattern = "needle"))

        assertEquals(listOf("/workspace/a.kt"), result.files.map { it.path })
    }

    @Test
    fun `grep rejects paths escaping the mapped root`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.grepRootfs(root, "/workspace/../../etc", WorkspaceGrepOptions(pattern = "root"))
        }
        assertTrue(error.message.orEmpty().contains("escapes"))
    }

    @Test
    fun `glob rejects paths escaping the mapped root`() {
        assertThrows(IllegalArgumentException::class.java) {
            manager.globRootfs(root, "/workspace/../..", WorkspaceGlobOptions(pattern = "*"))
        }
    }

    @Test
    fun `grep on a missing path fails`() {        assertThrows(IllegalArgumentException::class.java) {
            manager.grepRootfs(root, "/workspace/nope", WorkspaceGrepOptions(pattern = "x"))
        }
    }

    @Test
    fun `glob sorts by modification time newest first`() {
        val older = write("older.kt", "1")
        val newer = write("deep/newer.kt", "2")
        older.setLastModified(1_000_000L)
        newer.setLastModified(3_000_000L)

        val result = manager.globRootfs(root, "/workspace", WorkspaceGlobOptions(pattern = "**/*.kt"))

        assertEquals(listOf("/workspace/deep/newer.kt", "/workspace/older.kt"), result.entries.map { it.path })
        assertEquals(2, result.total)
        assertEquals(1, result.entries.last().sizeBytes)
    }

    @Test
    fun `glob honours head limit offset and skipped directories`() {
        repeat(4) { index -> write("f$index.kt", "x") }
        write("build/skip.kt", "x")

        val first = manager.globRootfs(
            root,
            "/workspace",
            WorkspaceGlobOptions(pattern = "*.kt", headLimit = 3),
        )
        val second = manager.globRootfs(
            root,
            "/workspace",
            WorkspaceGlobOptions(pattern = "*.kt", headLimit = 3, offset = 3),
        )

        assertEquals(4, first.total)
        assertEquals(3, first.entries.size)
        assertEquals(3, first.nextOffset)
        assertTrue(first.truncated)
        assertEquals(1, second.entries.size)
        assertNull(second.nextOffset)
        assertFalse(first.entries.any { it.path.contains("build/") })
    }

    // --- readLines -----------------------------------------------------------

    @Test
    fun `read lines honours offset and limit`() {
        write("long.txt", (1..20).joinToString("\n") { "line$it" } + "\n")

        val slice = manager.readRootfsLines(root, "/workspace/long.txt", offset = 5, limit = 3)

        assertEquals(listOf("line5", "line6", "line7"), slice.lines)
        assertEquals(5, slice.startLine)
        assertEquals(7, slice.endLine)
        assertEquals(20, slice.totalLines)
        assertTrue(slice.hasMore)
        assertEquals(8, slice.nextOffset)
    }

    @Test
    fun `read lines clips overly long lines`() {
        write("wide.txt", "y".repeat(2500) + "\nsecond\n")

        val slice = manager.readRootfsLines(root, "/workspace/wide.txt")

        assertEquals(WorkspaceLines.MAX_LINE_CHARS, slice.lines.first().length)
        assertEquals(1, slice.clippedLines)
        assertEquals("second", slice.lines[1])
    }

    @Test
    fun `read lines stops at the character budget on a line boundary`() {
        write("many.txt", (1..5000).joinToString("\n") { "z".repeat(40) } + "\n")

        val slice = manager.readRootfsLines(root, "/workspace/many.txt", limit = 5000)

        assertTrue(slice.lines.size < 5000)
        assertTrue(slice.lines.sumOf { it.length + 1 } <= WorkspaceLines.MAX_OUTPUT_CHARS)
        assertTrue(slice.hasMore)
        assertEquals(slice.lines.size + 1, slice.nextOffset)
        assertEquals(5000, slice.totalLines)
    }

    @Test
    fun `read lines on an empty file returns nothing`() {
        write("empty.txt", "")

        val slice = manager.readRootfsLines(root, "/workspace/empty.txt")

        assertTrue(slice.lines.isEmpty())
        assertEquals(1, slice.startLine)
        assertEquals(0, slice.endLine)
        assertEquals(0, slice.totalLines)
        assertFalse(slice.hasMore)
        assertNull(slice.nextOffset)
    }

    @Test
    fun `read lines beyond the end returns an empty slice`() {
        write("short.txt", "only\n")

        val slice = manager.readRootfsLines(root, "/workspace/short.txt", offset = 10)

        assertTrue(slice.lines.isEmpty())
        assertEquals(10, slice.startLine)
        assertEquals(9, slice.endLine)
        assertEquals(1, slice.totalLines)
        assertFalse(slice.hasMore)
        assertNull(slice.nextOffset)
    }

    @Test
    fun `read lines keeps a last line without trailing newline`() {
        write("nonl.txt", "first\nsecond")

        val slice = manager.readRootfsLines(root, "/workspace/nonl.txt")

        assertEquals(listOf("first", "second"), slice.lines)
        assertEquals(2, slice.totalLines)
        assertFalse(slice.hasMore)
    }

    @Test
    fun `read lines slices already loaded text for remote backends`() {
        val slice = WorkspaceLines.read("a\nb\nc\n", offset = 2, limit = 5)

        assertEquals(listOf("b", "c"), slice.lines)
        assertEquals(2, slice.startLine)
        assertEquals(3, slice.endLine)
        assertEquals(3, slice.totalLines)
        assertFalse(slice.hasMore)
    }

    @Test
    fun `read lines rejects a file over the size cap`() {
        write("cap.txt", "x")

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceLines.read(File(workspace, "cap.txt"), maxFileBytes = 0)
        }
    }

    @Test
    fun `search is pure and does not need a rootfs`() {
        val dir = tempFolder.newFolder("plain")
        File(dir, "a.kt").writeText("needle\n")

        val result = search.grep(dir, WorkspaceGrepOptions(pattern = "needle"))

        assertEquals(listOf("a.kt"), result.files.map { it.path })
        assertEquals(1, result.files.single().matchCount)
    }
}
