# 3.1.1-fork.41 更新说明

版本：`3.1.1-fork.41 / 3000045`；包名 `me.rerere.rikkahub.fork`；数据库仍为 schema 34。发布日期：2026-11-01（UTC+8）。

## 这一版是什么

fork.40 的全部内容，修复 PRoot 里的命令结束不了，另跟进官方 master `5ad19405`（2026-11-01，模型选择面板支持多关键词搜索）。官方最新正式版仍是 3.1.1。

## 修复：结束命令时真正结束进程

在手机上的 fork.40 实测到的问题：

| 操作 | fork.40 的实际表现 |
|---|---|
| `workspace_job_kill` | 返回失败，PRoot、shell 与命令全部还在运行 |
| `workspace_shell` 超时或取消 | 报告超时，命令仍在后台运行 |
| `workspace_shell_kill` | 只结束前台子进程；shell 继续执行后面的命令，记为退出码 0 |

前两项从更早的版本起就存在，第三项来自 fork.40 带入的后台命令预览。

原因，均在手机上逐项验证：

- Android 的 `Process.destroy()` 和 `destroyForcibly()` 都只发 SIGTERM，PRoot 忽略 SIGTERM。
- PRoot 收到 SIGQUIT 会结束所有被跟踪的进程，但 ART 在所有线程上屏蔽了 SIGQUIT，子进程继承屏蔽字（PRoot 的 `SigBlk` 为 `0x80001204`），信号一直挂起。
- SIGKILL PRoot 后，被跟踪的进程脱离跟踪、过继给 init，命令的后半段照样执行。
- 只给前台子进程发 SIGTERM 时 shell 不受影响，继续执行下一条命令。
- 测试期间几分钟内就出现了进程号复用，按旧的进程号快照发信号不安全。

修复依赖的行为也在手机上验证过：SIGKILL 初始 shell 后，PRoot 按 `--kill-on-exit` 结束其余进程并在 1 秒内退出；带 `wait` 的 TERM trap 让后台子进程收尾后再退出（2.1 秒），不带 `wait` 时子进程 33 毫秒内就被截断。

改动：

- 新增 `ProotProcess`，所有 PRoot 进程都经它启动。终止时信号直接发给 PRoot 跟踪的进程，父进程在前：先发 SIGTERM，宽限期过后发 SIGKILL，由 `--kill-on-exit` 带走其余进程；PRoot 卡住不退时最后才 SIGKILL 它自己。
- 每次启动在 PRoot 的环境变量里放一个随机标记，按标记、父进程号和 `TracerPid` 定位；每次发信号前重读 `/proc`，不会打到被复用的进程号。命令经 `env -i` 启动，标记不进入 Rootfs。
- 后台命令和 `workspace_job` 的脚本加 TERM trap：收到结束请求后不再执行剩余命令，等已启动的进程收尾，以 143 退出。宽限期：后台命令 3 秒，任务 2 秒。
- 前台 `workspace_shell` 超时与取消直接 SIGKILL 整棵进程树。

## 本次运行的检查

| 检查 | 结果 |
|---|---|
| 正式构建 | GitHub Actions `Fork Verify` run 41294941469：`assembleGithubRelease`（R8 + lintVital）通过，签名证书核对一致 |
| JVM 测试 | 198 个 suite、1352 项，0 失败、0 错误，1 项跳过（`PdfCorpusTest.extractCorpus`） |
| 新增单测 | `ProotProcessTest` 10 项：进程树定位与父进程优先的排序、信号目标、强制结束的兜底、进程号复用；其中 2 项用真实 bash 验证 trap（结束后不执行下一条命令、等后台进程收尾） |
| 对照 | 本地把 trap 换成不带 `wait` 或去掉 trap 后，“等后台收尾”一项按预期失败 |
| Android 模拟器 | API 34 x86_64，`connectedGithubDebugAndroidTest` 13 个 suite、50 项全部通过 |
| 设备实测 | fork.40 上复现了上表的三个问题，并验证了修复依赖的 PRoot 行为 |

第一次模拟器运行（run 41293917531）有 1 项失败：官方的 `MediaCreationRepositoryTest` 关库后，Room 的失效监听协程才开始运行，抛出 “Database is closed”，打断了随后的 `RetryDelayUiTest`（50 项只跑了 48 项）。与本版改动无关；本版让该测试关库前先 invalidate 分页源，run 41294941469 全部通过。只改了测试代码，安装包不受影响。

没有验证：装上 fork.41 后在手机上重跑这组结束命令的操作。修复依赖的 PRoot 行为都在手机上单独验证过，但带新代码的安装包还没在真机上跑过。

已知问题，与本版改动无关：PRoot 里的命令继承了 ART 对 SIGPIPE 的屏蔽，`yes | head -1` 这类管道的上游会打印 “Broken pipe” 并以 1 退出，而不是被信号静默结束。修复需要在启动命令前清空信号屏蔽字，之后再处理。

## 安装包

通用 APK 含 arm64-v8a 与 x86_64；与 fork.36–40 同包名、同签名，可直接覆盖升级，不必卸载。

- 文件：`rikkahub-3.1.1-fork.41.apk`
- 大小：`18,922,536` 字节（约 18.0 MiB）
- SHA-256：`a16a8eec9758765559b290064f6a7c99a2bab205d5874a24b884df3d555d9731`
- 签名证书 SHA-256：`cb897a361d49933cc7d033b7ff05b95fad6483c18bb4134b10119b0f2715bf89`，与 fork.38–40 一致
- 构建源码：`3bdd2fed`；tag `v3.1.1-fork.41` 指向其后只改说明文件的提交，构建用到的源码与 `3bdd2fed` 一致
- [固定版本 Release](https://github.com/682012/rikkahub/releases/tag/v3.1.1-fork.41) · [下载 APK](https://github.com/682012/rikkahub/releases/download/v3.1.1-fork.41/rikkahub-3.1.1-fork.41.apk) · [机器可读验收](verification/fork41.json)

旧版本 Release 全部保留，不被覆盖。
