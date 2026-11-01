package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ProotProcessTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val proc by lazy { temp.newFolder("proc") }

    private fun fakeProc(pid: Int, ppid: Int, tracer: Int, env: List<String> = emptyList()) {
        val dir = File(proc, "$pid").apply { mkdirs() }
        File(dir, "status").writeText("Name:\tx\nState:\tS (sleeping)\nPPid:\t$ppid\nTracerPid:\t$tracer\n")
        File(dir, "environ").writeBytes(env.joinToString("") { "$it\u0000" }.toByteArray())
    }

    /** 应用 100 启动的 PRoot 200 (标记 run-a, 跟踪 201~203) 与 300 (标记 run-b, 跟踪 301), 外加几个干扰项 */
    private fun sampleTree() {
        fakeProc(200, ppid = 100, tracer = 0, env = listOf("PROOT_TMP_DIR=/tmp", "RIKKAHUB_PROOT_RUN=run-a"))
        fakeProc(201, ppid = 200, tracer = 200, env = listOf("HOME=/root")) // 初始 shell
        fakeProc(202, ppid = 201, tracer = 200) // 命令
        fakeProc(203, ppid = 1, tracer = 200) // 脱离了进程树的守护进程
        fakeProc(300, ppid = 100, tracer = 0, env = listOf("RIKKAHUB_PROOT_RUN=run-b"))
        fakeProc(301, ppid = 300, tracer = 300)
        fakeProc(400, ppid = 100, tracer = 0, env = listOf("RIKKAHUB_PROOT_RUN=run-a-old")) // 前缀相同的别的标记
        fakeProc(500, ppid = 999, tracer = 0, env = listOf("RIKKAHUB_PROOT_RUN=run-a")) // 不是应用的子进程
        fakeProc(600, ppid = 100, tracer = 999, env = listOf("RIKKAHUB_PROOT_RUN=run-a")) // 被别的进程跟踪
        File(proc, "self").mkdirs()
    }

    private fun tree(marker: String = "run-a", signals: ProcessSignals = RecordingSignals()) =
        ProotProcessTree(ProotProcessTree.markerEntry(marker), parentPid = 100, procDir = proc, signals = signals)

    @Test
    fun `finds proot by marker and parent, and only its tracees`() {
        sampleTree()
        assertEquals(200, tree("run-a").prootPid())
        assertEquals(listOf(201, 202, 203), tree("run-a").tracees().sorted())
        assertEquals(300, tree("run-b").prootPid())
        assertEquals(listOf(301), tree("run-b").tracees())
        assertNull(tree("run-c").prootPid())
        assertEquals(emptyList<Int>(), tree("run-c").tracees())
    }

    @Test
    fun `tracees are ordered parents first even when pids wrapped around`() {
        fakeProc(200, ppid = 100, tracer = 0, env = listOf("RIKKAHUB_PROOT_RUN=run-a"))
        fakeProc(205, ppid = 200, tracer = 200) // 初始 shell, 进程号比它派生的进程大
        fakeProc(201, ppid = 205, tracer = 200)
        fakeProc(203, ppid = 201, tracer = 200)
        fakeProc(202, ppid = 1, tracer = 200) // 脱离了进程树的守护进程
        assertEquals(listOf(202, 205, 201, 203), tree().tracees())
    }

    @Test
    fun `destroy sends SIGTERM to every tracee and nothing else`() {
        sampleTree()
        val signals = RecordingSignals()
        val delegate = FakeProcess()
        ProotProcess(delegate, tree(signals = signals)).destroy()
        assertEquals(listOf("TERM 201", "TERM 202", "TERM 203"), signals.sent.sorted())
        assertEquals(1, delegate.destroyCalls)
    }

    @Test
    fun `destroyForcibly kills tracees and stops once proot exits`() {
        sampleTree()
        val delegate = FakeProcess()
        val signals = RecordingSignals { pid ->
            // 初始 shell 退出时 PRoot 按 --kill-on-exit 结束其余进程并退出
            if (pid == 201) {
                listOf(200, 201, 202, 203).forEach { File(proc, "$it").deleteRecursively() }
                delegate.alive = false
            }
        }
        ProotProcess(delegate, tree(signals = signals), roundMillis = 200).destroyForcibly()
        assertEquals(setOf("KILL 201", "KILL 202", "KILL 203"), signals.sent.toSet())
        assertEquals(3, signals.sent.size)
        assertFalse(delegate.isAlive)
        assertEquals(1, delegate.destroyForciblyCalls)
    }

    @Test
    fun `destroyForcibly falls back to killing proot when it does not exit`() {
        sampleTree()
        val delegate = FakeProcess()
        val signals = RecordingSignals { pid -> if (pid == 200) delegate.alive = false }
        ProotProcess(delegate, tree(signals = signals), killRounds = 2, roundMillis = 50).destroyForcibly()
        assertEquals(2, signals.sent.count { it == "KILL 201" })
        assertEquals("KILL 200", signals.sent.last())
        assertFalse(signals.sent.any { it.endsWith(" 300") || it.endsWith(" 301") })
        assertFalse(delegate.isAlive)
    }

    @Test
    fun `no signal is sent after proot has exited`() {
        sampleTree()
        val signals = RecordingSignals()
        val process = ProotProcess(FakeProcess().apply { alive = false }, tree(signals = signals))
        process.destroy()
        process.destroyForcibly()
        assertEquals(emptyList<String>(), signals.sent)
    }

    @Test
    fun `cached proot pid is revalidated before signalling`() {
        sampleTree()
        val signals = RecordingSignals()
        val tree = tree(signals = signals)
        assertEquals(200, tree.prootPid())
        // PRoot 退出后进程号被复用, 新进程没有本次的标记
        listOf(200, 201, 202, 203).forEach { File(proc, "$it").deleteRecursively() }
        fakeProc(200, ppid = 100, tracer = 0, env = listOf("RIKKAHUB_PROOT_RUN=other"))
        fakeProc(201, ppid = 200, tracer = 200)
        assertNull(tree.prootPid())
        tree.signalTracees(hard = true)
        tree.killProot()
        assertEquals(emptyList<String>(), signals.sent)
    }

    @Test
    fun `background kill stops at SIGTERM when the command exits within the grace period`() {
        val process = FakeProcess(onDestroy = { it.alive = false })
        ProotBackgroundCommand(process, File(temp.root, "bg.log"), "/tmp/bg.log").kill()
        assertEquals(1, process.destroyCalls)
        assertEquals(0, process.destroyForciblyCalls)
    }

    // 下面两项用真实 bash 验证终止脚本: 按 ProotProcess.destroy 的顺序, 先给 shell 再给它派生的进程发 SIGTERM

    @Test
    fun `terminable script exits with 143 instead of running the next command`() {
        val dir = temp.newFolder("fg")
        val shell = startScript(dir, "sleep 30; touch after")
        try {
            val shellPid = awaitPid(File(dir, "shell.pid"))
            val pids = awaitSleeps(shellPid, sleeps = 1)
            assumeSigtermDeliverable(pids)
            terminate(listOf(shellPid) + pids)
            assertTrue("shell still running, signalled $shellPid + $pids", shell.waitFor(10, TimeUnit.SECONDS))
            assertEquals(143, shell.exitValue())
            assertFalse("command list continued", File(dir, "after").exists())
        } finally {
            shell.destroyForcibly()
        }
    }

    @Test
    fun `terminable script waits for background cleanup before exiting`() {
        val dir = temp.newFolder("bg")
        val shell = startScript(
            dir,
            "(trap 'sleep 1; echo cleaned > cleaned; exit 0' TERM; sleep 30 & wait) & sleep 30; touch after",
        )
        try {
            val shellPid = awaitPid(File(dir, "shell.pid"))
            val pids = awaitSleeps(shellPid, sleeps = 2)
            assumeSigtermDeliverable(pids)
            terminate(listOf(shellPid) + pids)
            val log = { "signalled $shellPid + $pids, log: ${File(dir, "out.log").readText()}" }
            assertTrue("shell still running, ${log()}", shell.waitFor(10, TimeUnit.SECONDS))
            assertEquals(log(), 143, shell.exitValue())
            assertTrue("exited before background cleanup, ${log()}", File(dir, "cleaned").isFile)
            assertFalse("command list continued, ${log()}", File(dir, "after").exists())
        } finally {
            shell.destroyForcibly()
        }
    }

    private fun startScript(dir: File, command: String): Process {
        assumeTrue(File("/proc/self/status").isFile && File("/bin/bash").canExecute())
        return ProcessBuilder(
            "/bin/bash", "-c", PROOT_TERMINABLE_SCRIPT, "rikkahub", dir.absolutePath,
            "echo \$\$ > shell.pid; $command",
        )
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "out.log"))
            .start()
    }

    /**
     * 继承了屏蔽或忽略 SIGTERM 的环境里 trap 不会触发, 这种环境下跳过。
     * 读已 exec 的 sleep: 它的信号状态继承自 shell 启动时, 不受 shell 运行中临时屏蔽的影响
     */
    private fun assumeSigtermDeliverable(pids: List<Int>) {
        for (pid in pids) {
            val status = runCatching { File("/proc/$pid/status").readLines() }.getOrNull() ?: continue
            fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
            if (field("Name") != "sleep") continue
            val masked = (field("SigBlk")?.toLongOrNull(16) ?: 0L) or (field("SigIgn")?.toLongOrNull(16) ?: 0L)
            assumeTrue("SIGTERM is blocked or ignored", masked and SIGTERM_BIT == 0L)
        }
    }

    private fun awaitPid(file: File): Int {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            file.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("shell did not start")
    }

    /** 读真实 /proc 找出 [pid] 的所有后代进程, 值为进程名 */
    private fun descendants(pid: Int): Map<Int, String> {
        val procs = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val child = dir.name.toIntOrNull() ?: return@mapNotNull null
            val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return@mapNotNull null
            fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
            val ppid = field("PPid")?.toIntOrNull() ?: return@mapNotNull null
            Triple(child, ppid, field("Name").orEmpty())
        }
        val result = mutableMapOf<Int, String>()
        var frontier = setOf(pid)
        while (frontier.isNotEmpty()) {
            val next = procs.filter { it.second in frontier && it.first !in result }
            next.forEach { result[it.first] = it.third }
            frontier = next.map { it.first }.toSet()
        }
        return result
    }

    /**
     * 等到 [pid] 派生的 [sleeps] 个 sleep 都已 exec, 返回此时的所有后代。
     * fork 之后、exec 之前收到的 SIGTERM 会被继承来的 trap 吞掉, 正式代码靠宽限期后的 SIGKILL 兜底, 测试里要避开
     */
    private fun awaitSleeps(pid: Int, sleeps: Int): List<Int> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val all = descendants(pid)
            if (all.values.count { it == "sleep" } >= sleeps) return all.keys.toList()
            Thread.sleep(20)
        }
        error("expected $sleeps sleep processes under $pid")
    }

    private fun terminate(pids: List<Int>) {
        ProcessBuilder(listOf("/bin/sh", "-c", "kill -TERM \"\$@\"", "sh") + pids.map(Int::toString))
            .start()
            .waitFor(5, TimeUnit.SECONDS)
    }

    private class RecordingSignals(private val onKill: (Int) -> Unit = {}) : ProcessSignals {
        val sent = mutableListOf<String>()

        override fun terminate(pid: Int) {
            sent += "TERM $pid"
        }

        override fun kill(pid: Int) {
            sent += "KILL $pid"
            onKill(pid)
        }
    }

    private class FakeProcess(private val onDestroy: (FakeProcess) -> Unit = {}) : Process() {
        @Volatile
        var alive = true
        var destroyCalls = 0
        var destroyForciblyCalls = 0

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (alive) Thread.sleep(5)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (alive && System.nanoTime() < deadline) Thread.sleep(5)
            return !alive
        }

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException("running") else 0

        override fun isAlive(): Boolean = alive

        override fun destroy() {
            destroyCalls++
            onDestroy(this)
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalls++
            return this
        }
    }

    private companion object {
        const val SIGTERM_BIT = 1L shl 14
    }
}
