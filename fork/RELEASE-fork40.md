# 3.1.1-fork.40 更新说明

版本：`3.1.1-fork.40 / 3000044`；包名 `me.rerere.rikkahub.fork`；数据库仍为 schema 34。发布日期：2026-11-01（UTC+8）。

## 这一版是什么

fork.39 的全部内容（已跟进官方 master `a5db9c9b`，2026-10-29），加上官方还没合入 master 的 `feat/background-shell` 分支两个提交，作为**预览功能**带入。官方最新正式版仍是 3.1.1，master 在 10-29 之后没有新提交。

## 预览：后台运行命令

来自官方 `8a472304`（shell 支持后台运行命令）与 `d7627eff`（命令结束后经会话收件箱主动通知模型）。

- `workspace_shell` 新增 `run_in_background`：命令在后台启动，立即返回任务 ID，适合开发服务器、监视进程和长时间构建。
- `workspace_shell_output` 读取上次之后的新输出，可等待命令结束（最多 600 秒）；`workspace_shell_kill` 结束命令和它启动的全部进程。
- 命令结束后，结果经会话收件箱主动送给模型（`ConversationInbox` 取代原来的 `MessageQueue`），模型不用反复轮询。
- 运行期间有前台通知；删除会话时一并结束该会话的后台命令。日志在 Rootfs 的 `/tmp/rikkahub/background/`，模型可以直接 `tail`、`grep`。

它和本分支的 `workspace_job_start/status/kill` 并存：

| | 官方后台命令（预览） | 本分支 workspace_job |
|---|---|---|
| 归属 | 当前会话，删除会话即结束 | 独立任务，可跨会话查询 |
| 结束后 | 主动通知模型 | 模型用 `workspace_job_status` 查询 |
| 执行位置 | 只在本地 Rootfs | 本地 Rootfs 与 HTTP 远程执行 |
| 子代理 | 不提供 | 按子代理工具设置 |

子代理拿到的 `workspace_shell` 不带 `run_in_background`，也没有 output/kill 工具，避免子代理结束后留下没人管理的进程。SSH 工作区和 HTTP 远程执行不提供后台命令，仍按原来的方式执行。

这是官方尚未发布的功能，官方合入 master 时可能调整；下次跟进官方时以官方合入的版本为准。官方其余未合入 master 的分支也已核对：SSH 远程工作区、媒体创作、ChatGPT / Grok 账号登录的内容都已改写进 master，本分支随 master 已经包含；另外两个是 8 月后停更的旧重构分支。除后台命令外，没有需要另外带入的官方功能。

## 仓库整理

- 删除 fork.38 起误提交的 `workspace/.cxx`（165 个 CMake 构建产物）和 `SettingProviderPage.kt.orig`；`workspace/.gitignore` 增加 `/.cxx`、`/build`。
- 新增 `Fork Verify` 工作流：推送 `ci/**` 分支或手动触发时，在 GitHub Actions 上做签名正式构建并核对证书，跑 JVM 测试和 API 34 模拟器测试；不自动发布 Release。

## 本次运行的检查

| 检查 | 结果 |
|---|---|
| 正式构建 | GitHub Actions `Fork Verify` run 41269230304：`assembleGithubRelease`（R8 + lintVital）通过，签名证书核对一致 |
| JVM 测试 | 196 个 suite、1336 项，0 失败、0 错误，1 项跳过（`PdfCorpusTest.extractCorpus`） |
| Android 模拟器 | API 34 x86_64，`connectedGithubDebugAndroidTest` 13 个 suite、50 项全部通过 |
| 只含预览提交的前置构建 | `ci/fork40-pre`（run 41268407197）三项同样通过 |
| 官方差异审查 | 分叉相对官方 master 删改的 99 处代码逐块核对：83 处为分叉有意改造，16 处为无害差异，未发现丢失的官方功能 |

没有在真机或模拟器上跑一遍后台命令的完整对话流程；这部分由官方附带的 JVM 测试（ConversationInbox、AgentLoop、消息通知）和编译覆盖。没有用新包覆盖用户手机。

## 安装包

通用 APK 含 arm64-v8a 与 x86_64；与 fork.36–39 同包名、同签名，可直接覆盖升级，不必卸载。

- 文件：`rikkahub-3.1.1-fork.40.apk`
- 大小：`18,925,876` 字节（约 18.0 MiB）
- SHA-256：`58392310fcbceb70fcab06e4728ca6575ffa6746ef49da5ea98200858017d80e`
- 签名证书 SHA-256：`cb897a361d49933cc7d033b7ff05b95fad6483c18bb4134b10119b0f2715bf89`，与 fork.38、fork.39 一致
- 构建源码：`2b15b6ec`；tag `v3.1.1-fork.40` 指向其后的提交，只多了说明文件和仓库缓存清理（`ebd5502f`），构建用到的源码与 `2b15b6ec` 一致
- [固定版本 Release](https://github.com/682012/rikkahub/releases/tag/v3.1.1-fork.40) · [下载 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-fork.40/rikkahub-3.1.1-fork.40.apk) · [机器可读验收](verification/fork40.json)

旧版本 Release 全部保留，不被覆盖。
