# Root 工具箱（RootToolbox）

一个面向 Android Root 环境的 **Tricky Store `keybox.xml` 查看与管理工具**，使用 Kotlin + Jetpack Compose 编写，界面基于 [mochi-compose](https://github.com/feice11/mochi-compose)。

> [!WARNING]
> **使用前请阅读 [免责声明](#免责声明)。** 本工具需要 Root 权限并会写入 `/data/adb`，请务必自行备份数据；本项目不托管、不生成、不分发任何密钥材料。

## 功能

- **Root 环境检测**：授权状态、Shell 身份（uid）、`su` 路径、SELinux 状态、Root 管理方式、系统版本与设备型号；每次回到前台自动复测。
- **模块扫描**：识别 Tricky Store 模块是否安装及其版本，扫描 `/data/adb/modules/tricky_store`、`modules_update`、`teesimulator` 等候选目录。
- **Keybox 在线索引**：聚合 `evoker.qzz.io` 与 `keybox.kowx712.cc` 两个第三方目录，展示状态（有效 / 已过期 / 已吊销 / 未知）、三绿评级、更新时间、到期时间、证书链数量与 SHA-256。前者提供直链，可在应用内下载安装；后者的下载端点受 Cloudflare Turnstile 保护，应用会跳转浏览器，**不尝试绕过验证**。
- **安全的替换流程**：XML 合法性校验 → 备份原文件为 `.bak.<时间戳>` 并核对大小 → 写入临时文件 → `chmod` / `chown` / `mv` / `restorecon` → SHA-256 回读校验；任一步失败即保留备份并尝试回滚。存在多个候选目标时，必须先在「目标」页确认后才能安装。
- **诊断**：内存日志（上限 600 条）、崩溃报告、一键复制完整诊断报告便于反馈问题。
- **设置**：深色模式、Monet 动态取色、主题主色调整。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 设备 | Android 10（API 29）及以上 |
| 权限 | 已 Root（Magisk / KernelSU / APatch 等），且已授予本应用 Root 权限 |
| 依赖模块 | 需要读写 `keybox.xml` 时，须安装 Tricky Store 模块 |
| 编译 | JDK 17+（已在 JDK 21 验证）、Android SDK Platform 36、Gradle 8.14.3（仓库已带 Wrapper） |

未 Root 或未安装模块时，应用仍可正常启动并展示环境检测结果、在线索引与日志，仅写入类功能不可用。

## 构建

克隆本仓库后在项目根目录执行：

```bash
./gradlew :app:assembleDebug      # Windows: .\gradlew.bat :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。Release 构建：

```bash
./gradlew :app:assembleRelease
```

> **签名说明**：当前 `release` 构建复用 debug 签名，方便直接安装测试。正式分发前请改为你自己的签名配置，**切勿把 keystore 或签名凭据提交进仓库**。

### 依赖版本约束

本项目锁定 `mochi-compose:0.2.0` / `mochi-icons:0.2.0`，该版本依赖 Compose 1.10 与 Kotlin 2.3 系列。请勿单独升级 Compose：升级到 1.12 会连带要求 AGP 9.1 与 compileSdk 37。当前组合：

- AGP 8.13.2 · Kotlin 2.3.21 · Gradle 8.14.3
- Compose 1.10.6 · compileSdk / targetSdk 36 · minSdk 29
- `gradle.properties` 中的 `android.overridePathCheck=true` 用于兼容含非 ASCII 字符的工作目录路径，请保留。

## 目录结构

```
app/src/main/java/dev/mochi/rootbox/
├── core/        # Root 检测、Shell、模块扫描、Keybox 解析与安装、日志
├── data/        # 网络请求、设置、在线索引数据源、数据模型
├── ui/          # Compose 页面（首页、环境、模块、密钥、目标、诊断、设置、关于）
├── AppViewModel.kt
├── MainActivity.kt / RootToolboxApp.kt
└── RootToolboxApplication.kt
```

## 免责声明

- 本项目仅供 **技术学习与个人设备研究** 使用，按「原样」提供，不附带任何明示或默示担保。
- 应用内置的在线索引指向 `evoker.qzz.io` 与 `keybox.kowx712.cc` 等**第三方站点**。本项目与这些站点无任何关联，不托管、不生成、不存储、不分发任何 `keybox.xml`、证书或私钥，也不对其内容的真实性、合法性、来源、可用性与时效性作任何保证。相关内容的合法性及使用后果，由使用者自行判断并承担全部责任。
- Keybox 类文件通常涉及第三方厂商的密钥材料与使用条款。请确保你仅在**拥有合法授权**的设备与用途范围内使用，不得用于绕过他人的安全机制、规避服务条款、欺诈或任何违反所在地区法律法规及第三方权益的行为。
- Root 操作与修改 `/data/adb` 下的文件可能造成设备无法启动、数据丢失、失去保修或引入安全风险。操作前请自行完整备份，替换流程中的自动备份与回滚也不能保证覆盖所有意外情况。
- 因使用或无法使用本软件而产生的任何直接或间接损失，作者及贡献者不承担责任。详见 [LICENSE](LICENSE) 第 7、8 条。
- 本项目与 Magisk、KernelSU、APatch、Tricky Store、Google 及文中提及的第三方站点均无官方关联。

## 鸣谢

- [mochi-compose](https://github.com/feice11/mochi-compose) / `mochi-icons` —— iOS 风格的 Compose 主题与组件库。
- Android Jetpack Compose、Kotlin 及所有上游开源依赖。

## 贡献

欢迎提交 Issue 反馈问题，或通过 Pull Request 改进代码。提交前请确认不引入任何密钥、证书、账号凭据或本机绝对路径。

## 许可证

[Apache License 2.0](LICENSE) · Copyright 2026 Zyn
