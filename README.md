# RikkaHub · 个人增强分支

基于 [RikkaHub 官方 3.1.1](https://github.com/rikkahub/rikkahub/tree/3.1.1) 的非官方分支。保留官方聊天客户端，重点补上**多代理协作、后台长任务、自动压缩和工作区文件迁移**。

**当前版本：`3.1.1-fork.40` / `3000044` · 维护分支：`fork/3.1.1` · 数据库：schema 34。**

已跟进官方 master **`a5db9c9b`（2026-10-29）** 的全部10个后续提交，另带官方尚未合入 master 的后台命令（预览）；官方最新稳定版仍为3.1.1。

> 与官方团队没有隶属关系。本仓库记录个人修改，并在 GitHub Releases 保存已验安装包，方便重复下载；不提供官方或面向终端用户的技术支持。官方渠道下载的是官方版，不是此分支。上游版权与 AGPL-3.0 许可证保持不变。

[完整功能对比](fork/FEATURES.md) · [fork.40 更新与验收](fork/RELEASE-fork40.md) · [版本历史](fork/CHANGES.md) · [构建维护](fork/README.md) · [修改声明](NOTICE.md)

## 下载与重复使用

**[下载 fork.40 通用 APK（18.0 MiB）](https://github.com/682012/rikkahub/releases/download/v3.1.1-fork.40/rikkahub-3.1.1-fork.40.apk)** · [版本说明与 SHA-256](https://github.com/682012/rikkahub/releases/tag/v3.1.1-fork.40) · [最新正式版](https://github.com/682012/rikkahub/releases/latest)

固定版本链接不会随 nightly 被覆盖；每个正式版本保留对应源码 tag、APK 与校验文件。直接安装不需要编译环境，也不会附带维护者的聊天、账号或服务凭据。

同包名、同签名的 fork.36–39 可按常规覆盖升级，先备份聊天和设置；不要为升级先卸载。官方包与本分支不同，不能直接覆盖官方版。需要重新编译时见 [构建与维护](fork/README.md)，签名密钥和本机配置自行准备。

## 相比官方，到底增加了什么？

**按独立使用场景分为 5 组新增工作流、2 项主要功能增强，稳定性修复另列。** 这不是按按钮或提交数统计，也不把官方已有功能算成分支原创。

| 分类 | 能力 | 官方 3.1.1 已有 | 本分支的增量 |
|---|---|---|---|
| 新增 ① | 子代理协作 | 没有内置子代理任务管理 | 独立上下文/模型的任务；排队、等待、取结果、取消；默认并发 2，可设 1–4 |
| 新增 ② | 工作区后台任务 | shell 与终端 | `workspace_job_start/status/kill`；独立任务、状态和日志；超时可配，0 表示不限 |
| 新增 ③ | HTTP 远程执行 | SSH/SFTP 工作区 | 可选自托管 HTTP shell/job 后端；与 SSH 分开路由，不把命令误送到另一台机器 |
| 新增 ④ | 自动上下文压缩 | 手动压缩与检查点 | 达到模型窗口阈值时自动摘要，可指定压缩模型；保留原始历史 |
| 新增 ⑤ | ZIP 工作区迁移 | 文件操作、单文件/目录批量导出 | 整个文件区 ZIP 导出与导入新工作区；校验后发布，不覆盖已有工作区或聊天 |
| 增强 ① | 重试策略设置 | 自动重试开关，指数退避 | 次数 0–100；固定 30/60 秒、30–60 秒随机或自定义 1–600 秒，主/子代理共用 |
| 增强 ② | 单会话用量面板 | 请求用量记录、全局统计 | 聊天页按会话查看输入/输出/缓存与模型明细，包含关联子代理用量 |

计数说明：单会话用量面板是官方统计能力的增强；ZIP 是新增迁移流程，不代表官方没有文件导出。前两组共增加 **5 个子代理工具 + 3 个后台任务工具**，不能再把它们分别算成 8 组功能。[计数与源码依据 →](fork/FEATURES.md)

## fork.40 新增：官方后台命令（预览）

- 带入官方尚未合入 master 的 `feat/background-shell`：`workspace_shell` 可设 `run_in_background`，用 `workspace_shell_output` 读输出、`workspace_shell_kill` 结束，命令结束后主动通知模型。
- 与本分支 `workspace_job_*` 并存：官方后台命令归属当前会话，只在本地 Rootfs 提供；`workspace_job` 仍是可跨会话查询的独立长任务。子代理不拿后台命令工具。
- 预览功能以官方之后合入 master 的版本为准。[说明与验证 →](fork/RELEASE-fork40.md)

## fork.39 这次跟上了什么？

- 官方 BetterText Markdown：文本选择、公式、表格、代码高亮与颜色色块。
- 思考链折叠时保留工具审批；矮屏加号菜单支持滚动。
- OpenAI空/非标准role兼容、SAF导出、非英文余额格式、Tinyfish `purpose`等官方修复。
- 更新检查跟随本fork的GitHub Releases，不再误报“官方3.1.1是新版本”或引导下载不同签名包。
- **SSH实际跑通**：密钥导入、主机指纹确认、SFTP读文件和SSH终端 `pwd`；新渲染和审批可见也做了Android界面验证。

SSH入口：**设置 → 扩展 → 工作区 → 加号 → SSH远程工作区**。高级设置的“远程执行”是另一路HTTP服务。

[本轮全部官方提交、APK校验与验证边界 →](fork/RELEASE-fork39.md)

## fork.38 保留的功能


- **重试等待能真正修改**：固定 30 秒、60 秒，或每次在 30–60 秒内随机；自定义编辑器不会因碰巧输入预设值而消失。
- **工作区终于能导入 ZIP**：支持旧裸文件包、Unicode/二进制、空目录、有效空包；导入为新的本地工作区。也可明确选择去掉 `workspace/` 或 `files/` 外层目录。
- **导出内容可选**：跳过缓存/构建目录，或包含这些目录的全部普通文件；完成页展示跳过数量，不只显示“成功”。
- **补齐真实边界**：ZIP 路径/CRC/大小/权限校验、取消撞上提交的状态一致性、过期临时目录清理，以及真实 Android 驱动的大行读取回退。

**升级后的默认重试等待改为固定 60 秒。** 旧实现实际按 1、2、4…秒退避、上限 60 秒；这两件事不同。嫌等待长可在网络设置改为固定 30 秒或随机区间。

入口：**设置 → 网络 → 自动重试**；**工作区列表 → 工作区文件迁移**；高级设置中的工作区迁移入口使用同一套实现。

## 还有哪些加固？

流式生成约 1.5 秒节流保存、节点差分写入；超大消息失败时完整分片读取而非跳页；备份导入的用量表归一化、记忆冲突保留、FTS 对齐；长工具输出可读性保护；多会话通知隔离与精确清理。

这些属于数据保护、兼容和缺陷修复，**不计入新增功能数**。完整历史见 [CHANGES](fork/CHANGES.md)。

## 哪些功能来自官方？

**本地 PRoot/Linux 工作区、SSH/SFTP、MCP、Skills、文件式记忆与整理、会话历史检索、全局用量统计、多供应商聊天、Web UI、语音、媒体生成、搜索、翻译、后台悬浮窗和快速回复等，均继承上游。**

官方项目、下载渠道、赞助与贡献者信息请看 [上游仓库](https://github.com/rikkahub/rikkahub)。本分支不将上游的功能、赞助关系或支持渠道表述为自己的。

## 验证到哪里了？

| fork.39 本次检查 | 真实结果 |
|---|---|
| App / Workspace / AI / Common JVM | **1046项，144 suite，0失败/错误/跳过**；不沿用旧全模块计数 |
| 正式构建 | **R8 + lintVital 实际通过**，无fastBuild；签名与fork.38一致 |
| Android API34 x86_64模拟器 | 同签名覆盖升级；SSH密钥/指纹/SFTP读文件/终端pwd；公式/表格/高亮/色块；折叠审批可见 |
| 更新检测 | GitHub release转换3项测试通过，UI不再误报官方3.1.1更新 |
| 未验证 | 用户手机生产升级、厂商后台/锁屏、所有SAF提供商、真实上游搜索/余额及所有矮屏状态 |

[版本、校验信息与验证边界 →](fork/RELEASE-fork39.md) · [机器可读摘要 →](fork/verification/fork39.json)

## 使用前知道这些限制

- 子代理默认关闭；不支持嵌套、独立费用预算或子代理内审批/续跑。需要审批的操作交给父对话。并行能缩短可拆分任务的时间，不保证每个任务更快或更省。
- SSH 不提供 `workspace_job_*`。HTTP 模式只提供 shell/job，不提供远端文件工具；令牌具有命令执行权限，必须保密。
- **ZIP 只迁移文件区，不含 Linux Rootfs、应用数据库或整机。** 不恢复符号链接；旧包没有记录的权限不能凭空恢复。导入后如需命令执行，需要安装 Rootfs 并选择新工作区。
- 默认导入限额：100000 条目、12 GiB 展开、4 GiB 单文件/ZIP。分卷、加密或非 UTF-8 文件名明确拒绝；导出不构成运行中文件的原子快照。
- 前台服务不等于锁屏永不掉线；HTTP 服务重启后旧任务不会自动安全接管。消息 UI 尚未懒分页，不能保证任意大历史不 OOM。
- `me.rerere.rikkahub.fork` 与官方包不同，不能覆盖安装官方版；跨分支导入前保留原备份。子代理、压缩与重试都可能增加请求和费用，不提升模型本身的智力。

## 维护与许可

功能增量对比基线为官方 **3.1.1 / `ad2c4430`**；当前已合入截至2026-10-29的官方master **`a5db9c9b`**，不承诺永远领先后续官方版本。`master` 保留上游镜像，`fork/3.1.0` 与 `fork/3.0.0` 是历史分支。

[构建与回退](fork/README.md) · [AGPL-3.0](LICENSE) · [个人分支声明](DISCLAIMER.md) · [修改记录](NOTICE.md)

### English summary

Unofficial personal fork of RikkaHub **3.1.1**, aligned with upstream master **a5db9c9b (2026-10-29)** in fork.39. Preserves subagents, background jobs, HTTP execution, automatic compression, ZIP migration, configurable retries and per-chat usage. Adds upstream BetterText rendering, color swatches, visible pending approvals and compatible relay fixes. **1046 passing JVM tests**, a formal R8/lint build, and Android emulator SSH/SFTP/terminal and rendering checks. Signed APKs and checksums are in [GitHub Releases](https://github.com/682012/rikkahub/releases/tag/v3.1.1-fork.39); no end-user support is offered. See the [release notes](fork/RELEASE-fork39.md) for boundaries.
