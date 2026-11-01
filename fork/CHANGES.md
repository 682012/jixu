# 与原版的差异 / Differences from upstream

基线：RikkaHub `3.1.1`（commit `ad2c4430`）；上一版基于 `3.1.0`（`eeef60b2`）。

当前能力对比统一见 [FEATURES.md](FEATURES.md)：5 组新增工作流、2 项主要增强、稳定性修复与继承官方的能力分开说明。下面是按版本保留的历史记录，旧版本的行为不代表当前实现。

## fork.41：结束命令时结束整棵进程树（2026-11-01）

- 修复 PRoot 里的命令结束不了：`workspace_job_kill` 返回失败且任务继续运行；`workspace_shell` 超时或取消后命令仍在后台运行；`workspace_shell_kill` 只结束前台子进程，shell 继续执行后面的命令。均在手机上的 fork.40 实测
- 原因：Android 的 `destroy()`/`destroyForcibly()` 都只发 SIGTERM，PRoot 忽略；ART 屏蔽 SIGQUIT，PRoot 收到 SIGQUIT 结束全部进程的逻辑不会触发；SIGKILL PRoot 只会让命令脱离跟踪继续运行
- 新增 `ProotProcess`：按启动时的环境变量标记找到 PRoot，信号直接发给被跟踪的进程（父进程在前），由 `--kill-on-exit` 带走其余进程；每次发信号前重读 `/proc`，不会打到被复用的进程号
- 后台命令与任务在 TERM trap 下运行：收到结束请求后不再执行剩余命令，等已启动的进程收尾，以 143 退出
- 跟进官方 master `5ad19405`（模型选择面板多关键词搜索）
- 测试：官方 `MediaCreationRepositoryTest` 关库前不取消 Room 的失效监听，曾让一次模拟器测试中途崩溃；现在关库前先 invalidate
- GitHub Actions run 41294941469：JVM 1352 项 0 失败（新增 `ProotProcessTest` 10 项，含 2 项真实 bash 信号测试）、模拟器 50 项全过、R8 + lintVital 正式构建通过
- 版本 3.1.1-fork.41 / 3000045，schema 34 不变；APK 18,922,536 B，SHA256 a16a8eec9758765559b290064f6a7c99a2bab205d5874a24b884df3d555d9731；详见 [RELEASE-fork41.md](RELEASE-fork41.md)

## fork.40：带上官方后台命令预览（2026-11-01）

- 在 fork.39 上带入官方未合入 master 的 `feat/background-shell`（`8a472304`、`d7627eff`）作为预览：`workspace_shell` 的 `run_in_background`、`workspace_shell_output`、`workspace_shell_kill`，命令结束后经会话收件箱主动通知模型
- 与本分支 `workspace_job_*` 并存；后台命令只在本地 Rootfs 提供，子代理不拿后台参数和 output/kill，SSH 与 HTTP 远程执行不受影响
- 删除误提交的 `workspace/.cxx` 构建产物与 `.orig` 残留；新增 `Fork Verify` 工作流（签名构建、JVM、API 34 模拟器）
- GitHub Actions：JVM 1336 项 0 失败、模拟器 50 项全过、R8 + lintVital 正式构建通过；分叉相对官方 master 删改的 99 处代码逐块核对：83 处为分叉有意改造，16 处为无害差异，未发现丢失的官方功能
- 版本 3.1.1-fork.40 / 3000044，schema 34 不变；APK 18,925,876 B，SHA256 58392310fcbceb70fcab06e4728ca6575ffa6746ef49da5ea98200858017d80e；详见 [RELEASE-fork40.md](RELEASE-fork40.md)

## fork.39：跟进官方 master 与修正更新来源（2026-11-01）

- 官方最新稳定仍为3.1.1；本版合入master a5db9c9b（2026-10-29）后的完整10提交，并保留官方祖先便于后续三方合并
- BetterText Markdown、颜色码色块、折叠思考时保留工具审批、矮屏加号菜单滚动、空role兼容、SAF导出、locale余额、Tinyfish purpose
- 更新检查改走本fork的GitHub Release，修复当前fork被当成落后官方3.1.1的误报；不自动安装
- fork.38能力保留，版本3.1.1-fork.39/3000043，schema34不变
- 本次App707+Workspace55+AI278+Common6=1046项JVM全通过；R8和lintVital正式构建通过
- Android隔离模拟器实际验证同签名升级、SSH密钥导入/建连/SFTP读文件/终端pwd、新公式/表格/高亮/色块和待审批可见；不声称用户真机/所有厂商背景行为已验
- APK18,905,804B，SHA256 b95fb5792557a21396d47f3c809f26716092af22de59c48dc019f7a9a6231af9；详见[RELEASE-fork39.md](RELEASE-fork39.md)

## fork.38：双轮修复统一与工作区迁移（2026-10-25）

- 发布已验 `3.1.1-fork.38 / 3000042` 源码，数据库仍为 schema 34；两轮 fork.37 有效修复均保留
- 重试等待可选固定 30/60 秒、30–60 秒随机、自定义 1–600 秒；主对话/子代理共用，默认改为固定 60 秒
- ZIP 导入为独立本地工作区，支持旧裸包、Unicode、二进制、空目录/空包与大小写文件名，不覆盖原工作区或聊天
- ZIP 路径/CRC/大小/权限/ZIP64 校验；取消与提交回执一致，过期临时残骸按需清理；导出可选缓存过滤并显示跳过项
- 修正通知清理、job 列表后端边界，以及真实 Android 驱动的大行原生错误回退
- JVM 1392 项登记：1391 通过、0 失败、1 项可选 PDF 语料测试跳过；Android API34 模拟器 50 项全通过；Python 11/SQLite 38 及 Unicode 分片检查通过
- 最终 R8 + lintVital 通过，签名与前版一致；用户手机生产库升级、厂商通知与锁屏保活未验
- 首页按更严格的“5 组新增 + 2 项增强”重排，不把官方已有能力或 bug 修复凑成新功能；Material 依赖作为已验普通源码提交
- 源码同步后补发同一份已验 APK 至 [固定版本 Release](https://github.com/682012/rikkahub/releases/tag/v3.1.1-fork.38)，附 SHA256SUMS 并保留对应源码 tag；不重编译、不触发 nightly。完整变化见 [RELEASE-fork38.md](RELEASE-fork38.md)

## fork.37：3.1.1 合并后检修（2026-10-25）

- 后续源码同步：重写 GitHub 首页与差异表，明确 6 组新增能力和官方共有功能；更新维护/修改声明，不增加应用功能。维护分支 CI 改为仅手动触发，不因切换首页自动发布 nightly。

- 修复官方 3.1.1 schema 32 用量表与 fork 的同版本结构冲突；迁移共用幂等归一化，保留历史记录与用量
- 记忆路径冲突不再 REPLACE 覆盖：规范路径保留较新内容，另一份无损归档
- 同版本备份对齐只重放受管表索引，修复 FTS 索引 already exists 导致恢复失败
- SSH 工作区不再被全局 HTTP 远程执行开关劫持；工具清单、就绪状态、job 能力与实际后端一致
- HTTP/SSH 无法读取本地 `/tool_outputs` 时保留全文，修复父、子代理长输出不可恢复
- 超大消息行不再跳页：正常分页，CursorWindow 溢出整体按 Unicode code point 分片回退；保存快照与子代理会话尾部读取同样保护，尾部回退只读取请求的最近节点
- 历史内联媒体转换失败不再让 metadata 保存/会话删除被断言阻断，原始数据保留
- 远程日志按行或字节截断均标记；HTTP 失败保留有限、脱敏的可纠正原因，错误正文预览最多等 2 秒，成功请求的 0=不限语义不变
- CI 使用 `assembleGithubRelease` 并匹配 `apk/github/release/*.apk`，找不到 APK 时明确失败；本轮未触发云端发布
- Live Update 通知按会话隔离；工作区 job 与媒体创作前台服务通知 ID 分离；兼容 Android 13 以下通知权限检查
- 本地 ZIP 导出列表过滤 SSH 工作区；审批权限保持原样，不增加子代理审批豁免
- 复核并保留共享工作树补丁：SSH 的 `timeout=0` 真按不限等待且仍可取消；FTS 列结构兼容检查；旧记忆归档路径统一带前导斜杠
- 最终实际执行 App / Workspace / AI / Common 共 950 项 JVM 测试，136 个 suite，0 失败/错误/跳过；Python 11 项与 SQLite 模拟核验通过，最终 R8 + lintVital 构建通过，Android 仪器测试仅编译/打包，未在手机运行
- 版本 `3.1.1-fork.37` / `3000041`，数据库仍为 schema 34；验证与未验边界见 [AUDIT-20261025.md](AUDIT-20261025.md)

## fork.36：合并官方 3.1.1（2026-10-24）

- 合并官方 3.1.1 的 49 个提交：SSH 远程工作区、后台生成悬浮窗与快速回复、德语、记忆整理模型设置、工具调用执行耗时、models.dev 模型能力识别
- 界面跟进官方改动：图片裁剪重做、联网搜索入口移到加号菜单、压缩上下文保留条数改步进器、收藏模型排序、连按返回闪退等修复
- 数据库 schema 34：在 fork 33 上补 `workspaces.type` / `workspaces.ssh_config`，兼容官方 3.1.1 的 SSH 工作区；`30→32`、`31→32`、`32→33` 迁移链保留，33→34 幂等加列
- 全文搜索采用官方增量索引（去掉 jieba 词典，中文单字 NEAR 匹配），保留 fork 的 `ensureSchema` 分词器检测与重建兜底
- 工作区工具改用官方 `WorkspaceLayout` / `capabilities`：SSH 工作区不暴露本地写文件与 job 工具；fork 的工作区 job、远程 HTTP 执行、超时配置保留
- 子代理工具保留；父对话工具输出外置改用官方 `capabilities().rootfs` 判断，SSH/远程工作区不再误截断
- 版本 `3.1.1-fork.36` / `3000040`

## fork.35：审计修复与回归测试（2026-10-24）

- 修复增量落库被前置全删抵消：保留差分，减少长会话流式生成时的节点重写
- 工作区 ZIP 按目录剪枝，不跟随符号链接或特殊文件；取消扫描/大文件复制可及时停止；I/O 失败不再假报成功
- 导出页按名称选择工作区，文件选择器返回后沿用选中的 ID；不再固定导出第一个工作区
- 子代理继承全局网络重试设置；只在确有本地文件读取能力时外置长工具输出
- 远程 HTTP requestTimeoutSec=0 按不限处理；网络重试次数输入值与保存值一致
- 新增 41 项 App 回归测试；App 502 + Workspace 24 项全部通过
- 版本 `3.1.0-fork.35` / `3000039`；同签名 arm64 测试包已构建（fastBuild，无 R8），未自动安装或推 GitHub
- 没有收窄跨助手检索、剔除辅助请求用量或新增预算硬限制；前台服务重构未采纳
- 详见 [AUDIT-20261024.md](AUDIT-20261024.md)，区分代码、JVM、真机和发布状态

## fork.27–34：补齐此前未记录的变化

- fork.27：压缩摘要请求去掉工具定义，避免模型继续调用工具而不输出摘要
- fork.28–31：rkpro 设置入口/嵌入试验；入口未工作，方案放弃，不再沿用
- fork.32–33：回到纯版并删除 rkpro 设置卡片
- fork.34：新增工作区文件区 ZIP 导出；当前版本含网络最大重试次数设置与持久化
- fork.34 / 3000037 于 2026-10-23 同步到 GitHub；2026-10-24 审计前确认 2,381 个受跟踪文件与构建机完全一致

## fork.26：强杀不丢聊天记录、搜索默认全库

- 流式生成期间按 1.5 秒节流落库（原来只在生成结束/空闲时写），进程被强杀最多丢最近一次落库之后的内容
- App 退到后台（ON_STOP）和从最近任务划掉（前台服务 onTaskRemoved）时主动落库
- 消息节点改为增量保存：只写内容/顺序变化的节点，避免高频落库时反复重写整个长会话
- 搜索索引写失败不再连带会话保存失败
- 搜索页默认范围改为「全部助手」：迁移或换助手后，历史记录常不属于当前助手，原来默认只搜当前助手会全部落空
- 空闲落库等待从 5 秒降到 2 秒
- 版本 `3.1.0-fork.26` / `3000026`

## fork.25：技能删除与 Provider 类型标签修复

- 技能安装前校验 SKILL.md：name 去空白、长度不超过 64、不以 `.` 开头、禁 `/` 和控制字符、description 必填
- 修复技能删除链路
- Provider 设置页内置 ChatGPT / Grok 订阅类型，修复备份设置迁移后 provider 被过滤、登录选项消失
- 版本 `3.1.0-fork.25` / `3000025`

## fork.24：压缩提示词、隐藏失败的 MCP 工具

- 子代理工具不再枚举全部模型 ID（大账号模型池上千 UUID 会撑爆提示词）
- MCP 工具列表隐藏连接失败的项
- 版本 `3.1.0-fork.24` / `3000024`

## fork.23：修复旧记忆路径

- legacy 记忆文件路径补前导 `/`（`_legacy/imported_memories_*.md`）
- 数据库 schema 32 → 33
- 版本 `3.1.0-fork.23` / `3000023`

## fork.22：补齐 excp schema 31 备份迁移

- 兼容 excp 分支 schema 31：补 `ConversationEntity.config`，创建 `memory_file`/`memory_consolidation`/媒体创作/翻译/用量表
- 将旧 `MemoryEntity` 迁移到 `memory_file`
- 备份库没有 usage_record 时从消息回填用量
- 版本 `3.1.0-fork.22` / `3000022`

## fork.21：备份导入 schema 细节修复

- `usage_record.total_tokens` 去掉错误的 `DEFAULT 0`，与 Room schema 32 一致
- `31→32` 在 `usage_record` 不存在的分支补建三个索引
- 版本 `3.1.0-fork.21` / `3000021`

## fork.20：备份导入与 3.1 账号 Provider 修复

- `30→32` 迁移也清理旧 `ConversationEntity.chat_model_id` 和旧索引
- `DatabaseBackup.alignToTarget` 在 identity hash 相同但表结构不同时不再跳过对齐
- 备份设置迁移保留官方 3.1 的 `chatgpt` / `grok` Provider，不再把订阅登录入口过滤掉
- 版本 `3.1.0-fork.20` / `3000020`

## fork.19：还原 fork.18 对 Learning Mode 的改动

- fork.18 误改了 `LEARNING_MODE_PROMPT`，fork.19 恢复官方 3.1 原文
- 版本 `3.1.0-fork.19` / `3000019`

## fork.18：内置学习模式提示词不再覆盖用户设定

- `LEARNING_MODE_PROMPT` 改为补充性指南，明确服从助手系统提示词、用户指令和人格
- 用户明确要直接答案、非作业场景时不再强制“只引导不给答案”
- 版本 `3.1.0-fork.18` / `3000018`

## fork.17：修复 31→32 会话表迁移

- `31→32` 迁移会删除旧库 `ConversationEntity` 上 3.1 已移除的 `chat_model_id` 和旧索引，避免启动时 Room 校验失败
- 版本号仍沿用 3.1.0，`3.1.0-fork.17` / `3000017`

## fork.16：3.1.0 兼容

- 合并官方 3.1.0 的账号登录、官方用量统计、自动做梦、语音继续聆听、压缩保留条数、工作区图片 URI 等功能
- fork 自有 `usage_record` 与官方同名表合并为统一表；fork 数据库升到 schema 32
  - `30 -> 32`：官方 3.0 直接建统一表并回填消息用量
  - `31 -> 32`：同时兼容官方 3.1 的 schema 31 和 fork.15 的 schema 31，保留已有流水后重建
- 子代理用量改走官方 `UsageRecorder`，来源记为 `SUBAGENT`，不再由 fork 手写第二套记录
- 修复 fork.15 的设置持久化、旧会话截断恢复、本地 job 生命周期、远程取消和备份对齐继续保留

本文件只记录**用户可感知的功能与行为差异**，实现细节见 `fork/README.md`。

---

## 当前与官方的差异说明

旧版这里曾混用官方 3.0.0 的对照表，错误暗示 SSH、全局用量统计和历史检索都是本分支新增，也把早期工具正文预览当成当前策略。现移除过期的“当前功能”描述，保留上面的逐版记录。

- [六组新增能力与源码依据](FEATURES.md#六组新增能力)：子代理、后台任务、HTTP 执行、会话用量面板、自动压缩、文件区 ZIP 导出。
- [对已有能力的增强](FEATURES.md#对已有能力的增强不重复算新增)：持久化、大行读取、备份/记忆迁移、重试次数、工具输出/技能、搜索。
- [来自官方的能力](FEATURES.md#来自官方不作为本分支独创)：SSH/SFTP、MCP、Skills、历史检索、全局用量统计等不再重复计数。
- 当前未实现工作区 ZIP 导入、30–60 秒重试间隔设置、子代理嵌套/独立审批/独立费用预算、消息 UI 懒分页，不作可用功能宣传。
- [fork.37 审查报告](AUDIT-20261025.md)：950 项 JVM 通过与 R8 构建通过不等于真机全功能通过。
