package me.rerere.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * PRoot 进程, 终止时结束它跟踪的整棵进程树。
 *
 * Android 上直接对 PRoot 发信号结束不了命令 (均已在设备上实测):
 * - [Process.destroy] 与 [Process.destroyForcibly] 都只发 SIGTERM, PRoot 忽略 SIGTERM, 命令照常运行;
 * - PRoot 收到 SIGQUIT 会 SIGKILL 所有被跟踪的进程, 但 ART 在所有线程上屏蔽了 SIGQUIT,
 *   子进程继承屏蔽字, 信号一直挂起不会送达;
 * - SIGKILL PRoot 只会让被跟踪的进程脱离跟踪, 过继给 init 继续运行。
 *
 * 所以信号直接发给被跟踪的进程: 初始 shell 一退出, PRoot 的 --kill-on-exit 就会
 * SIGKILL 其余进程并随之退出。
 */
internal class ProotProcess(
    private val delegate: Process,
    private val tree: ProotProcessTree,
    private val killRounds: Int = KILL_ROUNDS,
    private val roundMillis: Long = ROUND_MILLIS,
) : Process() {
    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int = delegate.waitFor()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = delegate.waitFor(timeout, unit)

    override fun exitValue(): Int = delegate.exitValue()

    override fun isAlive(): Boolean = delegate.isAlive

    /**
     * 给所有被跟踪的进程发 SIGTERM, 不等待。
     * 后台命令与任务的脚本收到后不再执行剩余命令, 等派生的进程收尾后退出, 见 [PROOT_TERMINABLE_SCRIPT]
     */
    override fun destroy() {
        if (delegate.isAlive) tree.signalTracees(hard = false)
        delegate.destroy()
    }

    /** SIGKILL 所有被跟踪的进程并等 PRoot 退出; PRoot 卡住不退时最后才 SIGKILL 它自己 */
    override fun destroyForcibly(): Process {
        var interrupted = false
        var round = 0
        while (round < killRounds && delegate.isAlive) {
            tree.signalTracees(hard = true)
            if (awaitExit(roundMillis)) interrupted = true
            round++
        }
        if (delegate.isAlive) {
            tree.killProot()
            if (awaitExit(roundMillis)) interrupted = true
        }
        delegate.destroyForcibly()
        if (interrupted) Thread.currentThread().interrupt()
        return this
    }

    /** 不响应中断地等 PRoot 退出 (调用方可能正因中断在清理), 返回期间是否被中断过 */
    private fun awaitExit(millis: Long): Boolean {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (delegate.isAlive && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

    private companion object {
        const val KILL_ROUNDS = 3
        const val ROUND_MILLIS = 1_000L
        const val POLL_MILLIS = 20L
    }
}

/**
 * 按启动时写进 PRoot 环境变量的标记定位 PRoot, 再按 TracerPid 找出它跟踪的进程。
 *
 * 只认 [parentPid] 的直接子进程且带本次标记, 进程号被复用后不会误发信号;
 * 被跟踪的进程经 `env -i` 清空了环境变量, 不会被认成 PRoot。
 */
internal class ProotProcessTree(
    private val markerEntry: String,
    private val parentPid: Int,
    private val procDir: File = File("/proc"),
    private val signals: ProcessSignals = AndroidProcessSignals,
) {
    @Volatile
    private var cachedPid: Int? = null

    /** PRoot 的进程号; PRoot 已退出或还没执行起来时为 null */
    fun prootPid(): Int? {
        cachedPid?.let { if (isProot(it)) return it }
        val pid = procDir.listFiles().orEmpty().firstNotNullOfOrNull { dir ->
            dir.name.toIntOrNull()?.takeIf { isProot(it) }
        }
        cachedPid = pid
        return pid
    }

    /** PRoot 跟踪的所有进程, 包括脱离了进程树的守护进程 */
    fun tracees(): List<Int> {
        val proot = prootPid() ?: return emptyList()
        return procDir.listFiles().orEmpty().mapNotNull { dir ->
            dir.name.toIntOrNull()?.takeIf { statusField(it, "TracerPid") == proot }
        }
    }

    /** 给所有被跟踪的进程发 SIGKILL ([hard]) 或 SIGTERM */
    fun signalTracees(hard: Boolean) {
        tracees().forEach { pid -> if (hard) signals.kill(pid) else signals.terminate(pid) }
    }

    /** 最后的兜底: SIGKILL PRoot 自身, 仍被跟踪的进程会脱离跟踪 */
    fun killProot() {
        prootPid()?.let(signals::kill)
    }

    private fun isProot(pid: Int): Boolean =
        statusField(pid, "PPid") == parentPid &&
            statusField(pid, "TracerPid") == 0 &&
            markerEntry in environ(pid)

    private fun statusField(pid: Int, name: String): Int? = runCatching {
        File(procDir, "$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.toIntOrNull()
        }
    }.getOrNull()

    private fun environ(pid: Int): List<String> = runCatching {
        File(procDir, "$pid/environ").readBytes().toString(Charsets.UTF_8).split('\u0000')
    }.getOrDefault(emptyList())

    companion object {
        /** 写进 PRoot 环境变量的标记名, 值为每次启动生成的随机 ID */
        const val MARKER_ENV = "RIKKAHUB_PROOT_RUN"

        fun markerEntry(id: String) = "$MARKER_ENV=$id"
    }
}

/** 发给被跟踪进程的信号, 单测里换成记录调用的实现 */
internal interface ProcessSignals {
    fun terminate(pid: Int)

    fun kill(pid: Int)
}

internal object AndroidProcessSignals : ProcessSignals {
    override fun terminate(pid: Int) = send(pid, OsConstants.SIGTERM)

    override fun kill(pid: Int) = send(pid, OsConstants.SIGKILL)

    private fun send(pid: Int, signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (_: ErrnoException) {
            // 进程已经退出
        }
    }
}
