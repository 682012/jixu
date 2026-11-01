package me.rerere.rikkahub.data.ai.subagent

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.ai.retryPolicy
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.service.ChatGenerationForegroundService
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "SubagentRunner"
private const val HARD_MAX_CONCURRENT = 8
private const val HARD_MAX_STEPS = 120

/**
 * 子代理执行器。
 *
 * 与 MemoryConsolidator 同一套路：独立模型、独立工具集、独立 runAgent，
 * 区别是任务异步运行、支持多任务并发与结果落盘。
 */
class SubagentRunner(
    private val context: Application,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val usageRecorder: UsageRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, SubagentTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val permits = SubagentPermitPool()
    private val state = MutableStateFlow<List<SubagentTask>>(emptyList())

    /** 已经发过终态通知的 task；集合是"同一 task 只通知一次"的凭据。 */
    private val notified = ConcurrentHashMap.newKeySet<String>()

    /** 结果交付记录，按 task scope；subagent_wait 与 subagent_result 共用同一份。 */
    private val delivery = SubagentDeliveryRegistry()

    /**
     * 终态通知投递口：由 ChatService 在 init 里接到父会话的 ConversationInbox。
     *
     * 期望实现是"只放进收件箱"——`sessionManager.getOrCreate(id).inbox.post(key, notice)`：
     * 父会话正在生成时，下一步请求模型前自然取走；空闲时留在队列里，等用户下一条消息一起发出。
     * 不要在这里调 [me.rerere.rikkahub.service.ChatService.postNotice]，那条路径会 dispatchInbox，
     * 子代理结束会自己开父会话的一轮生成，等于替用户发起对话。
     *
     * 用可变注入而不是构造参数：ChatService 依赖 ChatToolFactory → SubagentRunner，
     * 构造期互相依赖会成环。未接线时通知只写日志，任务本身和 result/wait 读取都不受影响。
     */
    @Volatile
    var noticeSink: ((SubagentNoticeEvent) -> Unit)? = null

    fun observe(): StateFlow<List<SubagentTask>> = state.asStateFlow()

    fun markDelivered(taskId: String) = delivery.markDelivered(taskId)

    fun isDelivered(taskId: String): Boolean = delivery.isDelivered(taskId)

    fun get(id: String): SubagentTask? = tasks[id]

    fun list(): List<SubagentTask> = tasks.values.sortedByDescending { it.createdAt }

    /**
     * 启动一个子代理任务并立即返回。
     *
     * [tools] 由调用方传入，通常已经去掉 subagent_* 自身，避免递归。
     */
    fun start(
        prompt: String,
        tools: List<Tool>,
        modelId: Uuid? = null,
        conversationId: Uuid? = null,
        maxSteps: Int = 40,
        timeoutSec: Int = 900,
        systemPrompt: String? = null,
    ): SubagentTask {
        val id = Uuid.random().toString()
        val task = SubagentTask(
            id = id,
            prompt = prompt,
            modelId = modelId,
            conversationId = conversationId,
            resultPath = resultFile(id).absolutePath,
            logPath = logFile(id).absolutePath,
        )
        tasks[id] = task
        publish()
        val generationId = Uuid.random()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var acquired = false
            var foregroundStarted = false
            try {
                val settings = settingsStore.awaitLoaded()
                val cfg = settings.subagent
                check(cfg.enabled) { "Subagents are disabled" }
                pruneOld(cfg.keepDays)
                permits.acquire(cfg.maxConcurrent.coerceIn(1, HARD_MAX_CONCURRENT))
                acquired = true
                currentCoroutineContext().ensureActive()
                update(id) { it.copy(status = SubagentStatus.RUNNING, startedAt = System.currentTimeMillis()) }
                if (conversationId != null) {
                    foregroundStarted = ChatGenerationForegroundService.acquire(context, generationId, conversationId)
                }
                // Only retry before receiving a model response: replaying a tool-capable run can duplicate effects.
                var receivedResponse = false
                suspend fun attempt(chosen: Uuid?): Pair<AgentEvent.Finished?, Uuid> = runOnce(
                    id, settings, chosen, prompt, tools, maxSteps, timeoutSec, systemPrompt, cfg.sessionId,
                    onProgress = { receivedResponse = true },
                    conversationId = conversationId,
                )
                val primaryId = modelId ?: cfg.defaultModelId
                val (finished, usedModelId) = try {
                    attempt(primaryId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val fallback = cfg.fallbackModelId
                    if (!receivedResponse && fallback != null && fallback != primaryId) {
                        appendLog(id, "[fallback] ${e.message}")
                        attempt(fallback)
                    } else throw e
                }
                currentCoroutineContext().ensureActive()
                if (finished == null) {
                    update(id) { it.copy(status = SubagentStatus.TIMEOUT,
                        finishedAt = System.currentTimeMillis(), error = "Subagent timed out after ${timeoutSec}s") }
                } else {
                    val reason = finished.reason
                    val error = when (reason) {
                        is AgentFinishReason.AwaitingApproval -> "Tool approval is required. Run this operation in the parent conversation; child approval/resume is not supported."
                        AgentFinishReason.StepLimitReached -> "Maximum model steps reached; result may be incomplete."
                        AgentFinishReason.Completed -> null
                    }
                    val text = finished.message.toText()
                    resultFile(id).writeText(text)
                    update(id) { it.copy(
                        status = if (error == null) SubagentStatus.COMPLETED else SubagentStatus.FAILED,
                        finishedAt = System.currentTimeMillis(), result = text, error = error,
                        usage = finished.message.usage, steps = finished.message.getTools().size, modelId = usedModelId,
                    ) }
                    appendLog(id, "[done] ${reason::class.simpleName}, ${text.length} chars")
                }
            } catch (e: CancellationException) {
                update(id) { it.copy(status = SubagentStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis(), error = "Cancelled") }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "subagent $id failed", e)
                update(id) { it.copy(status = SubagentStatus.FAILED,
                    finishedAt = System.currentTimeMillis(), error = e.message ?: e::class.java.simpleName) }
                appendLog(id, "[error] ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    if (acquired) permits.release()
                    if (foregroundStarted) ChatGenerationForegroundService.release(context, generationId)
                }
                publish()
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { cause ->
            jobs.remove(id, job)
            if (cause is CancellationException) update(id) { old ->
                if (old.isFinished) old else old.copy(status = SubagentStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis(), error = "Cancelled")
            }
        }
        job.start()
        return task
    }

    fun cancel(id: String): Boolean {
        val job = jobs[id]?.takeIf { it.isActive } ?: return false
        job.cancel()
        update(id) { it.copy(status = SubagentStatus.CANCELLED, finishedAt = System.currentTimeMillis()) }
        publish()
        return true
    }

    suspend fun await(id: String, timeoutSec: Int = 0): SubagentTask? =
        awaitUntil(id, deadlineAfter(timeoutSec))

    /**
     * 同时等待多个任务，共用一个 timeout（不是 8 × timeout 串行），返回顺序与 [ids] 一致。
     *
     * 调用方负责先校验全部 id 的归属；这里只等待已经在册的任务。
     */
    suspend fun awaitAll(ids: List<String>, timeoutSec: Int = 0): List<SubagentTask?> {
        if (ids.isEmpty()) return emptyList()
        val deadline = deadlineAfter(timeoutSec)
        return coroutineScope {
            val waiting = ids.distinct().associateWith { id -> async { awaitUntil(id, deadline) } }
            ids.map { id -> waiting.getValue(id).await() }
        }
    }

    private fun deadlineAfter(timeoutSec: Int): Long =
        if (timeoutSec <= 0) Long.MAX_VALUE else System.currentTimeMillis() + timeoutSec * 1000L

    private suspend fun awaitUntil(id: String, deadline: Long): SubagentTask? {
        while (true) {
            val task = tasks[id] ?: return null
            if (task.isFinished || System.currentTimeMillis() >= deadline) return task
            delay(250)
        }
    }

    private suspend fun runOnce(
        id: String,
        settings: Settings,
        modelId: Uuid?,
        prompt: String,
        tools: List<Tool>,
        maxSteps: Int,
        timeoutSec: Int,
        systemPrompt: String?,
        sessionId: Uuid,
        onProgress: () -> Unit,
        conversationId: Uuid?,
    ): Pair<AgentEvent.Finished?, Uuid> {
        val cfg = settings.subagent
        val model = settings.findModelById(modelId, cfg.defaultModelId)
            ?: settings.getCurrentChatModel()
            ?: error("No model available for subagent")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model ${model.displayName}")
        val parentPrompt = systemPrompt ?: DEFAULT_SUBAGENT_PROMPT
        val params = backgroundTextGenerationParams(model, sessionId, settings.fastModelReasoningLevel)
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                val system = (listOf(parentPrompt) + tools.map { it.systemPrompt(model, messages) })
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                return StepRequest(
                    messages = if (system.isBlank()) messages else listOf(UIMessage.system(system)) + messages,
                    params = params,
                )
            }
        }
        // 重试策略与主对话共用设置里的"网络 → 自动重试"，不再硬编码 RetryPolicy.NETWORK；
        // 请求构造见 buildSubagentRequest（同包，可单测）。
        val request = buildSubagentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(providerSetting),
                UsageTag(UsageSource.SUBAGENT, conversationId = conversationId),
            ),
            providerSetting = providerSetting,
            prompt = prompt,
            tools = tools,
            maxSteps = maxSteps,
            settings = settings,
        )
        val timeoutMs = if (timeoutSec <= 0) 0L else timeoutSec * 1000L
        var latest: UIMessage? = null
        suspend fun collect(): AgentEvent.Finished? {
            var result: AgentEvent.Finished? = null
            runAgent(request, hooks).collect { event ->
                when (event) {
                    is AgentEvent.MessageUpdated -> {
                        onProgress()
                        latest = event.message
                    }
                    is AgentEvent.Finished -> { latest = event.message; result = event }
                    else -> Unit
                }
            }
            return result
        }
        try {
            val finished = if (timeoutMs == 0L) collect() else withTimeoutOrNull(timeoutMs) { collect() }
            return finished to model.id
        } finally {
            // A cancelled/timed-out run can already have consumed tokens and completed tool calls.
            withContext(NonCancellable) {
                latest?.let { message ->
                    val text = message.toText()
                    runCatching { resultFile(id).writeText(text) }
                    update(id) { it.copy(result = text, usage = message.usage, modelId = model.id,
                        steps = message.getTools().size) }
                }
            }
        }
    }

    private fun pruneOld(keepDays: Int) {
        val cutoff = if (keepDays > 0) System.currentTimeMillis() - keepDays * 86_400_000L else Long.MIN_VALUE
        val completed = tasks.values.filter { it.isFinished }.sortedByDescending { it.createdAt }
        completed.forEachIndexed { index, task ->
            if (index >= 100 || task.createdAt < cutoff) {
                if (tasks.remove(task.id, task)) {
                    notified.remove(task.id)
                    delivery.forget(task.id)
                }
            }
        }
        if (keepDays > 0) runCatching {
            File(context.filesDir, "subagents").listFiles().orEmpty().forEach { dir ->
                if (dir.isDirectory && dir.lastModified() < cutoff && jobs[dir.name]?.isActive != true) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun update(id: String, transform: (SubagentTask) -> SubagentTask) {
        var finished: SubagentTask? = null
        tasks.computeIfPresent(id) { _, old ->
            val updated = transform(old)
            if (isSubagentTerminalTransition(old, updated)) finished = updated
            updated
        }
        publish()
        finished?.let(::notifyFinished)
    }

    /**
     * 终态通知：同一 task 只会走到这里一次（[notified] 判定）。
     *
     * 只投递通知，不启动父会话生成、不跨会话发送；正文留在结果文件里等 subagent_result 分页读。
     */
    private fun notifyFinished(task: SubagentTask) {
        if (!notified.add(task.id)) return
        val conversationId = task.conversationId ?: return
        val sink = noticeSink
        if (sink == null) {
            Log.i(TAG, "subagent ${task.id} finished (${task.status}); no notice sink wired")
            return
        }
        runCatching {
            sink(
                SubagentNoticeEvent(
                    conversationId = conversationId,
                    key = subagentNoticeKey(task.id),
                    notice = buildSubagentNotice(task, resultText(task)),
                )
            )
        }.onFailure { Log.w(TAG, "subagent ${task.id} notice failed", it) }
    }

    /** 结果全文：内存优先，其次结果文件。所有状态可用（失败/超时/取消保留部分输出）。 */
    fun resultText(task: SubagentTask): String =
        task.result ?: task.resultPath?.let { path -> File(path).takeIf { it.isFile }?.readText() } ?: ""

    fun resultChars(taskId: String): Int = tasks[taskId]?.let { resultText(it).length } ?: 0

    /** 按 offset/limit 切一段结果；任务不存在返回 null。读结果不会消耗它。 */
    fun readResult(
        taskId: String,
        offset: Int = 0,
        limit: Int = SUBAGENT_RESULT_DEFAULT_LIMIT,
    ): SubagentResultPage? {
        val task = tasks[taskId] ?: return null
        return subagentResultPage(task = task, full = resultText(task), offset = offset, limit = limit)
    }

    private fun publish() {
        state.value = tasks.values.sortedByDescending { it.createdAt }
    }

    private fun dir(id: String): File = File(context.filesDir, "subagents/$id").apply { mkdirs() }

    private fun resultFile(id: String): File = File(dir(id), "result.md")

    private fun logFile(id: String): File = File(dir(id), "events.log")

    private fun appendLog(id: String, line: String) {
        runCatching {
            logFile(id).appendText("[${System.currentTimeMillis()}] $line\n")
        }
    }

    companion object {
        private const val DEFAULT_SUBAGENT_PROMPT = """
You are a focused subagent. You were spawned by a main assistant to complete one concrete task.
Work autonomously: use the provided tools, do not ask the user questions, and do not narrate your process.
When you are done, reply with a compact result: what you did, key findings, and any file paths or commands the parent needs.
If the task cannot be completed, say exactly what blocked you.
"""
    }
}

/**
 * 组装一次子代理运行的请求。
 *
 * 抽出来的原因：旧实现把 retry 硬编码成 RetryPolicy.NETWORK（固定 3 次），
 * 用户在"网络 → 自动重试"里关掉开关或改次数，对子代理完全无效；主对话走
 * Settings.retryPolicy()，子代理却仍是 3 次。现在两边用同一个函数，
 * 关掉自动重试（enableAutoRetry=false 或 maxRetries=0）时子代理也不再重试。
 *
 * 取消不会被当成网络故障重试：withRetry 在 catch 里先 ensureActive()，
 * 再判断 CancellationException，所以停止生成/取消任务不会重新拉起请求。
 *
 * maxSteps 仍然夹在硬上限内（模型侧参数的上限由 SubagentTools 负责）。
 */
internal fun buildSubagentRequest(
    provider: Provider<ProviderSetting>,
    providerSetting: ProviderSetting,
    prompt: String,
    tools: List<Tool>,
    maxSteps: Int,
    settings: Settings,
): AgentRequest = AgentRequest(
    provider = provider,
    providerSetting = providerSetting,
    messages = listOf(UIMessage.user(prompt)),
    tools = tools,
    stream = false,
    maxSteps = maxSteps.coerceIn(1, HARD_MAX_STEPS),
    retry = settings.retryPolicy(),
)
