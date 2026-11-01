# 个人分支：构建与维护

用户可见功能统一维护在 [仓库首页](../README.md) 与 [官方差异表](FEATURES.md)。本页记录构建、签名与回退；版本验收见 [fork.41 更新说明](RELEASE-fork41.md)。

## 当前维护对象

| 项目 | 值 |
|---|---|
| 官方基线 | `3.1.1` / `ad2c4430` |
| 维护分支 | `fork/3.1.1` |
| 当前版本 | `3.1.1-fork.41` / `3000045` |
| 包名 | `me.rerere.rikkahub.fork` |
| 数据库 | schema 34；与 fork.37 相同，不与官方编号混用 |
| 已验发布构建 | R8 release，arm64-v8a + x86_64 通用包，18,922,536 字节（GitHub Actions `Fork Verify`） |
| 构建 SHA-256 | `a16a8eec9758765559b290064f6a7c99a2bab205d5874a24b884df3d555d9731` |

`master` 是上游镜像，旧 fork 分支是历史版本。每个正式版的已验原包保存在对应 Release（当前 [fork.41](https://github.com/682012/rikkahub/releases/tag/v3.1.1-fork.41)），附 APK 与 SHA256SUMS；旧 nightly 不代表当前已验安装包。fork.40 起由 GitHub Actions `Fork Verify` 构建签名；旧包保留，不触发旧 nightly，也不替换其资产。

## 重复下载、复用与后续维护

- 直接使用：下载 Release APK 即可；固定版本链接长期用于取回同一份包，[最新正式版](https://github.com/682012/rikkahub/releases/latest) 用于找后续版本。
- 继续开发：从 Release 对应 tag 获取完整公开源码；保留上游许可证、Material 源码和测试。签名与本机配置不入库，干净克隆仍需准备下列构建环境和配置。
- 后续发布：沿用构建和测试流程，核对版本、签名、APK SHA-256，再以独立版本 tag 归档 APK 与校验文件；不复用旧版本号、不覆盖旧资产。`Fork Verify` 只在推送 `ci/**` 分支或手动触发时运行（签名构建并核对证书、JVM 测试、API 34 模拟器测试），不自动发布 Release；旧 nightly 仍只手动触发。
- 工作区复用：ZIP 可反复导入为独立文件区；不覆盖旧工作区，不含 Rootfs、聊天数据库或整机环境。App 数据与签名密钥分别备份。

## 构建与测试

JDK 21、Android SDK 37.2、Gradle 9.6；NDK 28.2.13676358、CMake 3.22.1；Web UI 需要 Node.js 22、pnpm 11，先在 `web-ui/` 运行 `pnpm install --frozen-lockfile`。Google Services 配置和签名放在本机，不提交真实凭据。

```bash
# 快速编译：不进行 R8，不能算正式优化包
./gradlew :app:assembleGithubRelease -PfastBuild --daemon

# 正式构建：R8 + release lintVital
./gradlew :app:assembleGithubRelease --daemon --max-workers=2

# 全模块 JVM，测试任务真实重跑
./gradlew testDebugUnitTest --rerun :app:testGithubDebugUnitTest --rerun

# 构建 Android 测试；只有执行到设备上才算仪器测试通过
./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
./gradlew :app:connectedGithubDebugAndroidTest
```

APK 目录为 `app/build/outputs/apk/github/release/`。3.1.1 有 github/play flavor，不能再用旧 `apk/release/` 路径。正式构建需要较多内存：Gradle 配置 6 GiB 堆，另有 Kotlin/R8/原生编译；不要在同一目录并发多份 release 构建。

`material3/material-color-utilities` 已作为**普通源码目录**提交，与已验构建内容一致；不再保留空 gitlink 或要求额外初始化子模块。上游许可证在该目录中保留。

## 签名与私密配置

`app/google-services.json` 需要匹配包名 `me.rerere.rikkahub.fork`；运行 debug/仪器测试时，另在 `app/src/debug/google-services.json` 配置 `me.rerere.rikkahub.fork.debug`。源码未附真实 Google 服务配置，不能把干净克隆等同于免配置构建。维护者同签名升级所用 keystore 也不公开；自行签名的包不能直接覆盖维护者签名的安装包。

手动 CI 使用 `KEY_BASE64`、`SIGNING_CONFIG`、`GOOGLE_SERVICES_JSON` 三项仓库 Secrets；只在自行构建时需要，下载本次 APK 不需要这些配置。此次 Release 上传的是先前已验原包，未重跑云端构建。

`local.properties`（不入库）：

```properties
storeFile=/private/path/fork.jks
storePassword=<local-secret>
keyAlias=fork
keyPassword=<local-secret>
```

同一包名、同一签名才能覆盖升级分支。不要把 keystore、口令、API key、远程 token、聊天数据库或手机备份放进仓库。源码备份不能代替数据备份。

## HTTP 后端

实现：[rk-exec-server.py](rk-exec-server.py)。要求非空 `RK_EXEC_TOKEN`，默认回环监听，`RK_EXEC_ROOT` 与 `RK_EXEC_JOBS` 控制目录。客户端仅接受 HTTPS，回环地址例外。

只接管本地类型工作区的 shell/job，不覆盖 SSH。没有远端文件工具或 job-list 接口；令牌具有命令执行权限，还可能进入设置备份，必须保密。服务重启后不会自动接管旧任务。

## 合并与发布纪律

- 使用现有 `fork/3.1.1` 分支历史增量提交，不以独立快照强推替换远端历史。
- 源码先与已验输入逐文件核对；只改文档不会扩大测试覆盖范围。
- 维护分支 CI 保持手动触发，不因推文档顺带发布 nightly。
- versionCode 独立递增，不套早期 `3000000 + fork.N` 公式。
- [CHANGES.md](CHANGES.md) 保留版本历史；[HANDOFF.md](HANDOFF.md) 是历史维护现场，不是当前用户说明。

## 回退

升级前备份聊天、设置和工作区。用 Git 历史或已验源码包回退代码；设备数据库必须单独处理，不要为了降级先卸载 App 再找数据。

ZIP 迁移只覆盖文件区，不含 Rootfs/数据库/整机；旧包未保存的符号链接和权限无法恢复。跨分支备份对齐只修改 staging 副本，不承诺所有分支业务数据都兼容。
