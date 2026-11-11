# Root 工具箱（RootToolbox）

一个面向 Android Root 环境的 **Tricky Store `keybox.xml` 查看与管理工具**，使用 Kotlin + Jetpack Compose 编写，界面基于 [mochi-compose](https://github.com/feice11/mochi-compose)。

> [!WARNING]
> **使用前请阅读 [免责声明](#免责声明)。** 本工具需要 Root 权限并会写入 `/data/adb`，请务必自行备份数据；本项目不托管、不生成、不分发任何密钥材料。

## 功能

- **Root 环境检测**：授权状态、Shell 身份（uid）、`su` 路径、SELinux 状态、Root 管理方式、系统版本与设备型号；每次回到前台自动复测。
- **模块扫描**：识别 Tricky Store 模块是否安装及其版本，扫描 `/data/adb/modules/tricky_store`、`modules_update`、`teesimulator` 等候选目录。
- **模块加载确认**：只读检查模块目录、`module.prop`、`disable` / `remove` / `update` 标记、待重启更新目录与 keybox 的 mtime / size / SHA-256，输出 **已加载 / 待重启 / 未安装 / 状态未知** 四态并给出原因。文件写成功不会被当成「已加载」；证据不足时一律显示「状态未知」。不再读取 Zygisk 开关——`magisk --sqlite` 在 KernelSU / APatch / Zygisk Next 等场景读不到或读到过期值，会把已启用的设备误判成未加载。
- **Keybox 在线索引**：聚合 `evoker.qzz.io` 与 `keybox.kowx712.cc` 两个第三方目录，展示状态（有效 / 已过期 / 已吊销 / 未知）、三绿评级、更新时间、到期时间、证书链数量与 SHA-256。前者提供直链，可在应用内下载；后者的下载端点受 Cloudflare Turnstile 保护，应用会跳转浏览器，**不尝试绕过验证**。
- **安装预览（两阶段提交）**：点条目先下载到内存并解析，展示来源、大小、SHA-256、DeviceID、算法、证书数、最早到期、索引评级、完整目标路径、设备当前文件摘要与差异；校验不通过时禁止进入确认态。确认后才真正写入，也可从本地导入 `xml` 走同一条预览链路。
- **安全的替换流程**：XML 合法性校验 → 备份原文件为 `.bak.<时间戳>` 并核对大小 → 写入临时文件 → `chmod` / `chown` / `mv` / `restorecon` → SHA-256 回读校验；任一步失败即保留备份并尝试回滚。可选「写入前校验设备凭据」。
- **备份中心**：扫描目标目录下的 `keybox.xml.bak.*` / `*.bak`，解析时间戳（文件名 + `stat` 校正）、大小、SHA-256 与证书信息；支持只读预览、一键恢复、删除单个、按「保留最近 N 个」批量清理。恢复同样走上面的备份→写入→校验→回滚链路，不会直接 `cp`。
- **密钥检测**：走 Android 官方密钥认证（Keystore attestation）。生成一次性密钥、把证书链逐级校验到 Google 认证根、核对认证质询，并比对内置的 Google 吊销列表，最终给出 **有效**（主题色）/ **无效**（红色）。展示证书链、可信执行环境、引导状态、系统版本、安全补丁、构建指纹、信任根与认证质询。**无需 Root、不联网、不上传证书**；每次冷启动自动检测一次，切换后台不重复检测，也可在首页卡片或页面内手动重新检测。
- **模块更新检查**：扫描 `/data/adb/modules/*/module.prop` 的 `updateJson`，同时对照对应仓库的 GitHub Release，提示可更新版本；可下载 zip 并在模块行内查看实时进度、随时取消，下载完成后按钮直接变成「打开 / 删除」。临时文件集中管理、支持一键清空（两级确认）。安装包只存 app 私有目录，不写入 `/data/adb`；**不提供刷写功能**。
- **诊断**：内存日志（上限 600 条）、崩溃报告、一键复制完整诊断报告便于反馈问题。
- **设置**：深色模式、Monet 动态取色、主题主色、启动时检测开关、密钥检测明细开关与超时时间、备份保留数量、写入前凭据校验。

界面全部基于 [mochi-compose](https://github.com/feice11/mochi-compose)：底部标签栏带融球水滴拖尾，切换板块用组件库 `MochiBlurCrossfade` 做纯淡入（`blur = 0.dp` 时不建离屏模糊图层，避免整页录制开销）；模块检查时则反过来，只让 `MochiListRow` 的 value 槽位走组件库默认的 8dp 模糊替换。两套参数各自按需使用。；首页为「Root 权限整行卡片 + 每行两个的目录卡片」布局，卡片带容器变形转场与按压形变/高光，列表、对话框、分段控件、开关、进度环等均使用库组件。

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
├── core/        # Root 检测、Shell、模块扫描/加载/更新、Keybox 解析与安装、备份、密钥认证
├── data/        # 网络请求、设置、在线索引数据源、数据模型、安装预览模型
├── ui/          # Compose 页面（首页、环境、模块、模块更新、密钥库、预览、备份、密钥检测、目标、诊断、设置、关于）
├── AppViewModel.kt
├── MainActivity.kt / RootToolboxApp.kt
└── RootToolboxApplication.kt
```

## 密钥检测（Key Attestation）

用 Android 系统的密钥认证能力判断设备密钥是否可信：在 `AndroidKeyStore` 里生成一把带 `attestationChallenge` 的一次性 EC 密钥，取回证书链后：

1. 逐级校验签名（叶证书 → 中间证书 → 根证书）；
2. 要求链的锚点与内置的 **Google 硬件认证根**公钥一致；
3. 解析证书里的 KeyDescription 扩展，核对 challenge 与本次随机数一致；
4. 用叶证书序列号比对内置的 Google 吊销列表快照；
5. 读取 `attestationSecurityLevel`（TEE / StrongBox / Software）与 `RootOfTrust`（引导状态、是否锁定）。

判定规则：证书链可信 + 质询匹配 + 未被吊销 + 硬件级密钥 → **有效**；否则 **无效** 并给出具体原因。引导加载程序是否解锁只作为明细展示，不直接改变结论——解锁设备同样可以拿到可信的硬件认证链。整页无需 Root、不发起网络请求，证书不上传。

检测时机：只在**冷启动**（`viewModel.start()`）或用户手动点「检测 / 重新检测」时执行；切换后台再回来不会重新检测，Root 状态同理。首次检测使用内置吊销列表快照，不随 Google 后续更新自动同步。

> 参考实现：[vvb2060/KeyAttestation](https://github.com/vvb2060/KeyAttestation)。本项目的判定、证书解析与 UI 为自行实现，仅内置其公开的 Google 认证根与吊销列表数据。

## 模块更新检查

扫描每个模块 `module.prop` 里的 `updateJson`（Magisk 模块更新约定），并尝试从该地址或 `zipUrl` 推断 GitHub 仓库、对照最新 Release 的 tag 与 zip 资源。两个来源任一可用即可下载；只有 `versionCode` 变大或版本字符串变化时才提示「有新版本」。

- **不会自动联网**：进入页面不检查，必须点「检查更新」。检查期间先把扫到的模块名摆进列表，只有版本号那处文字用组件库的 `MochiListRow` value 槽位（`ListBlurText`）做模糊替换；标题、状态标签、按钮与进度全程清晰，不再整块模糊。结果返回后一次性替换。
- **排序**：有更新的模块排最前，未提供 `updateJson` 的模块沉到最底；模块行只显示当前/最新版本与紧凑状态标签，不展示更新说明。
- **下载进度内联且可取消**：进度环与进度条显示在对应模块自己的行里，不单设「下载中」板块；下载中可点「取消下载」，取消会 cancel 协程并断开连接（阻塞中的 `read` 立即返回），不落半成品文件、不记网络失败日志、不误报「下载失败」。下载完成后，原来的「下载新版本」按钮直接变成「打开 / 删除」两个按钮。
- 下载的 zip 保存在 app 私有目录 `filesDir/module-updates/`，不写 `/data/adb`，不需要额外权限。「打开」经 `FileProvider` 以 `content://` 临时授权给外部应用查看。
- 临时文件可查看、单个删除、一键清空；删除与清空都有二次确认。**本应用不提供模块刷写功能**，如需安装请自行在模块管理器中操作。

> 该功能是「检查 + 下载 + 管理安装包」，不修改系统分区、不调用模块管理器，也不保证第三方 `updateJson` 的可用性与真实性。

## 已知限制与风险

- **密钥检测依赖厂商密钥认证**：设备需要有可用的 TEE / StrongBox 与 Google 认证根；模拟器、部分无 GMS 认证的设备或 ROM 只能得到软件证书，会显示「无效」，原因里会写明是根不受信还是软件级密钥。
- **吊销列表是内置快照**：不联网、不自动更新，新吊销的密钥可能暂时识别不出来；需要最新列表时请更新 App。
- **引导状态不影响有效/无效**：解锁 Bootloader 的设备仍可能拿到可信认证链，页面会用明细里的「引导加载程序」单独标注。
- **模块加载状态是静态证据**：本应用不读取运行中进程的内存，无法证明模块此刻确实已注入；`已加载` 仅表示模块目录、标记文件与 keybox 一致且无待重启更新。
- **备份与回滚不能覆盖所有意外**：写入过程若遇到断电、文件系统错误或第三方模块并发改写，仍可能损坏配置，请自行保留离线备份。
- **在线索引与更新源可用性不受本项目管理**：两个第三方站点、模块 `updateJson` 与 GitHub Release 的内容、状态标注与下载地址随时可能变化；查询失败时只提示原因，不猜测有更新。
- **「打开」依赖设备上的第三方应用**：系统里没有能处理 zip 的应用时会提示失败；本应用不内置文件查看器，也不负责安装模块。

## 免责声明

- 本项目仅供 **技术学习与个人设备研究** 使用，按「原样」提供，不附带任何明示或默示担保。
- 应用内置的在线索引指向 `evoker.qzz.io` 与 `keybox.kowx712.cc` 等**第三方站点**。本项目与这些站点无任何关联，不托管、不生成、不存储、不分发任何 `keybox.xml`、证书或私钥，也不对其内容的真实性、合法性、来源、可用性与时效性作任何保证。相关内容的合法性及使用后果，由使用者自行判断并承担全部责任。
- Keybox 类文件通常涉及第三方厂商的密钥材料与使用条款。请确保你仅在**拥有合法授权**的设备与用途范围内使用，不得用于绕过他人的安全机制、规避服务条款、欺诈或任何违反所在地区法律法规及第三方权益的行为。
- Root 操作与修改 `/data/adb` 下的文件可能造成设备无法启动、数据丢失、失去保修或引入安全风险。操作前请自行完整备份，替换流程中的自动备份与回滚也不能保证覆盖所有意外情况。
- 因使用或无法使用本软件而产生的任何直接或间接损失，作者及贡献者不承担责任。详见 [LICENSE](LICENSE) 第 7、8 条。
- 本项目与 Magisk、KernelSU、APatch、Tricky Store、Google 及文中提及的第三方站点均无官方关联。

## 鸣谢

- [mochi-compose](https://github.com/feice11/mochi-compose) / `mochi-icons` —— iOS 风格的 Compose 主题与组件库。
- [vvb2060/KeyAttestation](https://github.com/vvb2060/KeyAttestation) —— 密钥检测的参考实现与 Google 认证根 / 吊销列表数据来源。
- [google/android-key-attestation](https://github.com/google/android-key-attestation) —— Google 官方密钥认证示例（Apache-2.0），内置认证根取自该项目。
- Android Jetpack Compose、Kotlin 及所有上游开源依赖。

## 贡献

欢迎提交 Issue 反馈问题，或通过 Pull Request 改进代码。提交前请确认不引入任何密钥、证书、账号凭据或本机绝对路径。

## 许可证

[Apache License 2.0](LICENSE) · Copyright 2026 Zyn
