package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 工作区运行时设置。
 *
 * 与上游 Settings 分文件维护，减少后续合并 3.0.x 时的冲突面。
 */
@Serializable
data class WorkspaceRuntimeSettings(
    /** workspace_shell 的默认超时（秒）。0 表示不限制。 */
    val commandTimeoutSec: Int = 30,
    /** 模型可以指定的最大超时（秒）。0 表示不设上限。 */
    val maxTimeoutSec: Int = 0,
    /** 后台任务日志/元数据的保留天数。 */
    val keepJobsDays: Int = 7,
    /** 远程执行后端。null 表示只用本地 proot。 */
    val remote: RemoteExecutionSettings? = null,
)

@Serializable
data class RemoteExecutionSettings(
    val enabled: Boolean = false,
    /** 例如 http://43.248.3.158:18080 */
    val baseUrl: String = "",
    val token: String = "",
    /** 远程工作目录，服务端会在此目录下执行命令。 */
    val workdir: String = "~/rk-work",
    /** 单次请求的超时（秒），0 表示不限制（服务端可能仍有自己的上限）。 */
    val requestTimeoutSec: Int = 0,
)

/**
 * 子代理设置。
 *
 * modelIds 是允许子代理使用的模型池；运行时按任务选择，defaultModelId 为空时
 * 取父会话模型。fallbackModelId 只在主模型永久性失败（4xx/空输出/非法 JSON）时使用。
 */
/**
 * 自动压缩：上下文占用达到模型窗口的阈值时，先插入一条摘要检查点再继续对话。
 * 摘要不会删除历史，原始消息仍然保留。
 */
@Serializable
data class AutoCompressSettings(
    val enabled: Boolean = true,
    /** 触发百分比，80 表示用到窗口的 80% 时压缩。 */
    val thresholdPercent: Int = 80,
    /** 生成摘要用的模型；null 表示跟随当前对话模型（保持提示词缓存命中）。 */
    val modelId: Uuid? = null,
)

@Serializable
data class SubagentSettings(
    val enabled: Boolean = false,
    /** 同时运行的最大子代理数；硬上限 8（见 SubagentRunner.HARD_MAX_CONCURRENT）。 */
    val maxConcurrent: Int = 4,
    val maxSteps: Int = 40,
    /** 单个子代理的总超时（秒），0 表示不限制。 */
    val timeoutSec: Int = 900,
    /** 子代理结果与日志保留天数。 */
    val keepDays: Int = 7,
    val modelIds: List<Uuid> = emptyList(),
    val defaultModelId: Uuid? = null,
    val fallbackModelId: Uuid? = null,
    val includeWorkspace: Boolean = true,
    val includeSearch: Boolean = false,
    val includeMcp: Boolean = false,
    /** 子代理可检索并读取其他会话（只读）。 */
    val includeChatHistory: Boolean = true,
    /** 子代理共用的会话 ID，让服务端命中系统提示与工具定义的缓存。 */
    val sessionId: Uuid = Uuid.random(),
    /** 允许子代理再创建子代理。默认关闭，避免递归放大成本。 */
    val allowNested: Boolean = false,
)
