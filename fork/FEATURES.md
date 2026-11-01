# 相比官方 3.1.1：新增工作流、功能增强与限制

对比功能增量的基线为官方 **3.1.1 / `ad2c4430`**；当前 **`3.1.1-fork.40 / 3000044`** 已跟进官方 master **`a5db9c9b`（2026-10-29）**，另带官方预览分支 `feat/background-shell`，数据库 schema 34。[本轮更新与实际验证](RELEASE-fork40.md)。不沿用早期版本的“官方没有”结论。

## 怎样数功能？

**5 组新增工作流 + 2 项主要功能增强；兼容与稳定性修复单列，不计入新增数。**

一组按独立使用目的计数，不按按钮、端点或提交数凑数。旧版说明的“6 组新增”把单会话用量面板也计入了新增；此处明确将它归为官方统计功能的增强。ZIP 导入与导出合为一组文件迁移流程，不拆成两个数字。这是计数更准确，不是删掉功能。

## 五组新增工作流

### 1. 子代理协作

**官方已有**：模型调用、工具、MCP、Skills，以及会话历史检索。官方 3.1.1 没有本分支的子代理任务管理器。

**本分支增加**：`subagent_run`、`subagent_wait`、`subagent_result`、`subagent_list`、`subagent_cancel`。每个任务独立上下文和模型，支持排队、超时、取消、结果与日志落盘。默认并发 2，可设置 1–4。

子代理可以按设置继承工作区、MCP 和官方历史工具；历史检索本身不算新增。fallback 只在尚未收到模型响应的安全边界尝试，不借重试重放已经执行的工具。

入口：高级与长任务 → 子代理。默认关闭；不支持嵌套、独立费用预算或子代理内审批/续跑。需要审批的操作由父对话处理。并行有时更快，但增加请求、内存和模型用量。

源码：[SubagentRunner](../app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentRunner.kt)、[SubagentTools](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/SubagentTools.kt)。

### 2. 工作区后台任务

**官方已有**：本地 PRoot/Linux 工作区、shell、终端；官方 shell 默认超时 30 秒、最高 600 秒。

**本分支增加**：`workspace_job_start/status/kill`，启动后立即返回任务 ID，之后查询状态、日志或停止任务。默认/最大命令超时可配置，0 表示不限。本地后台任务申请前台服务与 wakelock；启动被拒绝时不放出无保护的任务。

fork.40 另带官方预览的后台命令（`workspace_shell` 的 `run_in_background`，配 `workspace_shell_output/kill`），属于官方能力，不计入本分支新增。两者并存：官方后台命令归属当前会话，结束后主动通知模型，只在本地 Rootfs 提供，子代理不拿；`workspace_job` 是可跨会话查询的独立任务，也支持 HTTP 远程执行。

边界：本地 Rootfs 与 HTTP 执行后端支持，SSH 工作区没有 job 通道。App 重启后历史记录不等于还持有进程，不能用旧 PID 不加校验地接管或杀任务。前台服务也不等于锁屏永不掉线。

源码：[WorkspaceJobManager](../workspace/src/main/java/me/rerere/workspace/WorkspaceJobManager.kt)、[WorkspaceRepository](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt)、[前台服务](../app/src/main/java/me/rerere/rikkahub/service/WorkspaceJobForegroundService.kt)。

### 3. 自托管 HTTP 远程执行

**官方已有**：SSH/SFTP 工作区，命令与文件都在所选 SSH 主机。

**本分支增加**：可选的 `/exec`、`/job/start`、`/job/status`、`/job/kill` 协议，把本地类型工作区的 shell/job 交给自己部署的服务器执行。与官方 SSH 是不同通道；全局 HTTP 开关不会劫持 SSH 工作区。

边界：HTTP 模式没有远端文件工具，用远端 shell 操作文件；本机文件区不会自动同步过去。服务端要求非空 Bearer token，客户端要求 HTTPS（回环除外）。没有多租户目录隔离，服务重启后不会自动接管旧任务；也没有 HTTP job-list 端点，调用时明确说明不支持。

源码：[服务端](rk-exec-server.py)、[RemoteExecutionClient](../app/src/main/java/me/rerere/rikkahub/data/repository/RemoteExecutionClient.kt)、[后端选择策略](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceExecutionPolicy.kt)。

### 4. 自动上下文压缩

**官方已有**：手动压缩对话与检查点机制；记忆整理是另一个官方功能。

**本分支增加**：新用户轮次开始前，按对话模型的窗口和阈值自动生成摘要；默认阈值 80%，可选独立压缩模型。摘要作为检查点插入，本轮输入及原始历史保留，不删除聊天记录。

边界：阈值依赖 `contextLength` 设置正确；摘要请求会消耗用量，单独的摘要模型也可能窗口不足。摘要失败不阻断原对话。不是“无限上下文”，也不是长期记忆整理的替代品。

源码：[AutoCompressionPlan](../app/src/main/java/me/rerere/rikkahub/service/AutoCompressionPlan.kt)、[ChatService](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt)、[ChatRequestFactory](../app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt)。

### 5. ZIP 工作区文件迁移

**官方已有**：文件导入/导出与向目录批量导出，不能说官方完全没有导出。

**本分支增加**：选择整个本地文件区生成 ZIP，并把 ZIP 导入为**新的本地工作区**。旧版裸文件 ZIP、有效空包、Unicode 名称与内容、二进制、空目录、大小写不同的文件名均支持；`workspace/` 或 `files/` 外层需明确选择去掉。

导入先在独立临时目录验证，全部通过再发布并注册；失败/取消不伪装成功，提交后的取消不会删掉已注册文件。校验路径、CRC、实际大小、重复冲突、ZIP64 与权限；ZIP 内记录的 0600/0700 不放宽。过期临时残骸按需清理，活跃导入跳过。

导出可选择过滤缓存/构建目录，或包含这些目录的全部普通文件；完成页显示跳过数量。始终排除 `.jobs`、符号链接和特殊文件，不宣称原子快照。

入口：工作区列表 → 工作区文件迁移；高级与长任务中也有入口。只迁移 `/workspace` 文件区，**不含 Linux Rootfs、聊天数据库、设置或整机**。导入不覆盖旧工作区或修改原会话绑定；运行命令前需安装 Rootfs 并自行选择新工作区。

默认限额：100000 条目、12 GiB 展开、4 GiB 单文件/压缩包。分卷、加密、非 UTF-8 文件名明确拒绝；不恢复符号链接，旧导出没有记录的权限不能凭空还原。

源码：[WorkspaceFilesImporter](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceFilesImporter.kt)、[WorkspaceFilesExporter](../app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceFilesExporter.kt)、[WorkspaceTransferCard](../app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTransferCard.kt)。

## 两项主要功能增强

| 增强 | 官方 3.1.1 | 本分支 |
|---|---|---|
| 重试策略 | 自动重试开关；按 1、2、4…秒指数退避，最高 60 秒 | 最大次数 0–100；固定 30/60 秒、30–60 秒均匀随机整秒、自定义 1–600 秒范围。主对话和子代理共用；关闭/0 次/取消不重试 |
| 单会话用量面板 | 已有 UsageRecorder、usage_record 表、全局统计 | 聊天页按会话聚合输入/输出/缓存、命中率和模型明细，包含关联子代理用量；不是重新发明用量记录，不计算真实账单或虚构缺失历史 |

**默认等待已改为固定 60 秒**，与旧版“指数退避、上限 60 秒”不同。可按需要改为 30 秒或随机区间。只重试匹配的网络异常，不保证所有 429/5xx 都会重试。

源码：[重试设置](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/RetryDelaySettings.kt)、[重试策略](../ai/src/main/java/me/rerere/ai/agent/Retry.kt)、[UsageSheet](../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/UsageSheet.kt)、[UsageRecordDAO](../app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt)。

## 另列的稳定性与兼容修复

流式 1.5 秒节流落库、节点差分写入、超大消息完整分片回退、跨分支备份 staging 对齐、用量表归一化、记忆路径冲突保留、FTS 自检与对齐、工具全文可读性、技能元数据校验、通知身份和生命周期、SSH/HTTP 路由边界等。

fork.38 还用真实 Android 原生窗口发现了 requery 的特殊错误文本：只在精确的 `Couldn't read row N column M` 与 `CursorWindow.nativeGet*` 堆栈同时匹配时回退，分片失败仍整体抛错。没有新增 UI 懒分页，不能承诺无限大历史不 OOM。

## 来自官方，不计入分支新增

本地 PRoot、SSH/SFTP、MCP、Skills、记忆文件与整理、历史检索、请求记录与全局统计、多供应商聊天、Web UI、TTS/ASR、媒体生成、搜索/翻译、后台悬浮窗、快速回复、工具耗时、models.dev 能力识别，以及 fork.40 带入的官方预览后台命令等继承上游。

## 验证与取舍

最终全模块 JVM **1392 项登记，1391 通过、零失败、1 项可选 PDF 语料测试跳过**；Android API34 x86_64/KVM **50 项全部通过**；Python 11、SQLite 模拟 38 及 Unicode 分片检查通过；正式 R8 与 lintVital 已执行通过。用户真机生产数据库升级、厂商通知与锁屏保活未验。

这个分支用更多设置、工具和后台逻辑换取可操作性；不提升模型本身的智力，也不保证更快或更便宜。不需要这些增量时，官方版更省心。

[本版更新与验收](RELEASE-fork38.md) · [历史记录](CHANGES.md) · [维护方法](README.md) · [修改声明](../NOTICE.md)
