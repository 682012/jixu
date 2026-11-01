package me.rerere.workspace

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 在后台运行的命令, stdout 与 stderr 合并写入日志文件。
 *
 * 方法都是阻塞 IO, 调用方负责切到 IO 线程。
 */
interface WorkspaceBackgroundCommand {
    /** 日志文件在执行环境内的绝对路径, 模型可以用 shell 直接读取 */
    val logPath: String

    /** 阻塞到命令退出, 返回退出码 */
    fun waitFor(): Int

    /** 日志当前的字节数 */
    fun outputSize(): Long

    /** 从 [offset] 起读取至多 [maxBytes] 字节的日志 */
    fun readOutput(offset: Long, maxBytes: Int): ByteArray

    /** 终止命令及其派生的所有进程, 阻塞到进程退出 */
    fun kill()
}

/**
 * PRoot 内的后台命令, [process] 是 [ProotProcess]。
 *
 * 终止时先给命令派生的所有进程发 SIGTERM: 脚本不再执行剩余命令 (见 [PROOT_TERMINABLE_SCRIPT]),
 * 派生的进程有一段宽限期收尾; 宽限期过后 SIGKILL 整棵进程树。
 */
internal class ProotBackgroundCommand(
    private val process: Process,
    private val logFile: File,
    override val logPath: String,
) : WorkspaceBackgroundCommand {
    override fun waitFor(): Int = process.waitFor()

    override fun outputSize(): Long = logFile.length()

    override fun readOutput(offset: Long, maxBytes: Int): ByteArray {
        if (!logFile.isFile) return ByteArray(0)
        return RandomAccessFile(logFile, "r").use { file ->
            val available = (file.length() - offset).coerceAtLeast(0)
            val buffer = ByteArray(minOf(available, maxBytes.toLong()).toInt())
            file.seek(offset)
            file.readFully(buffer)
            buffer
        }
    }

    override fun kill() {
        process.destroy()
        if (process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS)) return
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val TERMINATE_GRACE_MS = 3_000L
        const val KILL_WAIT_MS = 2_000L
    }
}
