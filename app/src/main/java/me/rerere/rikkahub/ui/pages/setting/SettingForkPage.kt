package me.rerere.rikkahub.ui.pages.setting

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTransferCard
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTransferVM
import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import me.rerere.rikkahub.data.datastore.SubagentSettings
import me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * fork 专属设置：工作区长任务与子代理。
 *
 * 单独一页，避免在上游设置页里插入大量行，后续合并 3.0.x 时只需处理一个入口。
 */
@Composable
fun SettingForkPage(vm: SettingVM = koinViewModel()) {
    val transferVM: WorkspaceTransferVM = koinViewModel()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val runtime = settings.workspaceRuntime
    val sub = settings.subagent
    val runner = remember { org.koin.java.KoinJavaComponent.getKoin().get<me.rerere.rikkahub.data.ai.subagent.SubagentRunner>() }
    val tasks by runner.observe().collectAsStateWithLifecycle()

    fun updateRuntime(fn: (WorkspaceRuntimeSettings) -> WorkspaceRuntimeSettings) {
        vm.updateSettings { it.copy(workspaceRuntime = fn(it.workspaceRuntime)) }
    }

    fun updateSub(fn: (SubagentSettings) -> SubagentSettings) {
        vm.updateSettings { it.copy(subagent = fn(it.subagent)) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("高级与长任务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("上下文") }) {
                    switchItem(
                        checked = settings.autoCompress.enabled,
                        onCheckedChange = { on ->
                            vm.updateSettings { it.copy(autoCompress = it.autoCompress.copy(enabled = on)) }
                        },
                        headlineContent = { Text("自动压缩") },
                        supportingContent = { Text("上下文占用达到模型窗口的阈值时，先插入摘要再继续对话。原始消息不会被删除。") },
                    )
                    formItem(
                        headlineContent = { Text("触发阈值（%）") },
                        supportingContent = { Text("默认 80。窗口大小取模型设置里的 contextLength。") },
                    ) {
                        NumberField(settings.autoCompress.thresholdPercent) { v ->
                            vm.updateSettings {
                                it.copy(autoCompress = it.autoCompress.copy(thresholdPercent = v.coerceIn(10, 100)))
                            }
                        }
                    }
                    formItem(
                        headlineContent = { Text("压缩模型") },
                        supportingContent = { Text("生成摘要用的模型。不选则跟随当前对话模型；选了以后手动压缩和自动压缩都用它。") },
                    ) {
                        ModelPickerRow(
                            selected = settings.autoCompress.modelId,
                            providers = settings.providers,
                        ) { id ->
                            vm.updateSettings {
                                it.copy(autoCompress = it.autoCompress.copy(modelId = id))
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("工作区命令") }) {
                    formItem(
                        headlineContent = { Text("默认超时（秒）") },
                        supportingContent = { Text("workspace_shell 未指定 timeout 时使用。0 表示不限制。") },
                    ) {
                        NumberField(runtime.commandTimeoutSec) { v ->
                            updateRuntime { it.copy(commandTimeoutSec = v) }
                        }
                    }
                    formItem(
                        headlineContent = { Text("最大超时（秒）") },
                        supportingContent = { Text("模型可以指定到多少秒。0 表示不设上限。") },
                    ) {
                        NumberField(runtime.maxTimeoutSec) { v ->
                            updateRuntime { it.copy(maxTimeoutSec = v) }
                        }
                    }
                }
            }

            item {
                val remote = runtime.remote ?: RemoteExecutionSettings()
                CardGroup(title = { Text("远程执行后端") }) {
                    switchItem(
                        checked = remote.enabled,
                        onCheckedChange = { on ->
                            updateRuntime {
                                it.copy(remote = (it.remote ?: RemoteExecutionSettings()).copy(enabled = on))
                            }
                        },
                        headlineContent = { Text("启用远程执行") },
                        supportingContent = {
                            Text(
                                "长时间任务交给服务器执行，不受 Android 后台限制。" +
                                    "只对本地工作区生效：SSH 工作区的命令始终在 SSH 主机上执行，不被这个开关改道，" +
                                    "也没有 /job 后台任务接口。"
                            )
                        },
                    )
                    formItem(headlineContent = { Text("服务地址") }) {
                        OutlinedTextField(
                            value = remote.baseUrl,
                            onValueChange = { v ->
                                updateRuntime { it.copy(remote = (it.remote ?: RemoteExecutionSettings()).copy(baseUrl = v)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("http://host:18080") },
                        )
                    }
                    formItem(headlineContent = { Text("访问令牌") }) {
                        OutlinedTextField(
                            value = remote.token,
                            onValueChange = { v ->
                                updateRuntime { it.copy(remote = (it.remote ?: RemoteExecutionSettings()).copy(token = v)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                    }
                    formItem(headlineContent = { Text("远程工作目录") }) {
                        OutlinedTextField(
                            value = remote.workdir,
                            onValueChange = { v ->
                                updateRuntime { it.copy(remote = (it.remote ?: RemoteExecutionSettings()).copy(workdir = v)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("子代理") }) {
                    switchItem(
                        checked = sub.enabled,
                        onCheckedChange = { on -> updateSub { it.copy(enabled = on) } },
                        headlineContent = { Text("启用子代理工具") },
                        supportingContent = { Text("模型可以派生独立子任务并行执行，结果回传到主对话。") },
                    )
                    formItem(
                        headlineContent = { Text("最大并发") },
                        supportingContent = { Text("默认 4，最多 8；每个子代理都有独立上下文，内存紧张时可调低到 1。") },
                    ) {
                        NumberField(sub.maxConcurrent) { v -> updateSub { it.copy(maxConcurrent = v.coerceIn(1, 8)) } }
                    }
                    formItem(headlineContent = { Text("最大步数") }) {
                        NumberField(sub.maxSteps) { v -> updateSub { it.copy(maxSteps = v.coerceIn(1, 120)) } }
                    }
                    formItem(
                        headlineContent = { Text("单任务超时（秒）") },
                        supportingContent = { Text("0 表示不限制；超时会取消子代理并保留部分结果。") },
                    ) {
                        NumberField(sub.timeoutSec) { v -> updateSub { it.copy(timeoutSec = v) } }
                    }
                    formItem(
                        headlineContent = { Text("默认模型") },
                        supportingContent = { Text("不选则跟随主会话模型。") },
                    ) {
                        ModelPickerRow(
                            selected = sub.defaultModelId,
                            providers = settings.providers,
                        ) { id -> updateSub { it.copy(defaultModelId = id) } }
                    }
                    formItem(headlineContent = { Text("失败回退模型") }) {
                        ModelPickerRow(
                            selected = sub.fallbackModelId,
                            providers = settings.providers,
                        ) { id -> updateSub { it.copy(fallbackModelId = id) } }
                    }
                    switchItem(
                        checked = sub.includeWorkspace,
                        onCheckedChange = { on -> updateSub { it.copy(includeWorkspace = on) } },
                        headlineContent = { Text("子代理可使用工作区") },
                    )
                    switchItem(
                        checked = sub.includeMcp,
                        onCheckedChange = { on -> updateSub { it.copy(includeMcp = on) } },
                        headlineContent = { Text("子代理可使用 MCP 工具") },
                    )
                }
            }

            item {
                CardGroup(title = { Text("子代理任务") }) {
                    if (tasks.isEmpty()) {
                        item(headlineContent = { Text("暂无任务") })
                    } else {
                        tasks.take(10).forEach { task ->
                            val elapsed = ((task.finishedAt ?: System.currentTimeMillis()) -
                                (task.startedAt ?: task.createdAt)) / 1000
                            item(
                                headlineContent = { Text(task.prompt.take(60)) },
                                supportingContent = {
                                    Text(
                                        "${task.status.name} · ${elapsed}s · " +
                                            (task.error ?: task.modelId?.toString() ?: "")
                                    )
                                },
                                trailingContent = {
                                    if (!task.isFinished) {
                                        TextButton(onClick = { runner.cancel(task.id) }) {
                                            Text("取消")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item {
                WorkspaceTransferCard(transferVM) { id ->
                    navController.navigate(Screen.WorkspaceDetail(id))
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "这些设置会随 settings.json 一起备份与恢复。升级上游版本时，这里的数据结构保持不变。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun NumberField(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(6)
            text = filtered
            filtered.toIntOrNull()?.let(onChange)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ModelPickerRow(
    selected: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Uuid?) -> Unit,
) {
    val state = rememberModelListState(
        modelId = selected,
        providers = providers,
        type = ModelType.CHAT,
    )
    ModelListSheet(state = state, onSelect = { model -> onSelect(model.id) })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { state.open() }) {
            Text(
                state.currentModel?.displayName?.ifBlank { state.currentModel?.modelId }
                    ?: "跟随主会话"
            )
        }
        if (selected != null) {
            TextButton(onClick = { onSelect(null) }) { Text("清除") }
        }
    }
}
