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
- **密钥检测**：向系统取设备自己的 Google 认证密钥（AndroidKeystore attestation），**不读取 keybox.xml、不需要 Root**。页面最顶部显示根证书等级（谷歌远程密钥配置 / 谷歌硬件认证根证书 / AOSP软件认证根证书 / 三星Knox认证根证书 / OEM认证根证书 / 未知根证书），紧接着显示**引导加载程序是否锁定**，下面是证书链（使用者 / 不早于 / 不晚于 / 序列号 / 有效性）、可信执行环境、构建指纹、信任根（VerifiedBootKey / VerifiedBootHash / VerifiedBootState / deviceLocked）与认证质询。证书只在本机解析、不上传；**吊销状态用 Google 官方列表的本地缓存**判断：拉取一次后缓存 24 小时，过期会提示更新，可在设置或检测页手动「获取最新吊销列表」（国内网络可能需要 VPN）。每次冷启动自动检测一次，切换后台不重复检测，可在首页卡片手动重新检测。
- **模块更新检查**：扫描 `/data/adb/modules/*/module.prop` 的 `updateJson`，同时对照对应仓库的 GitHub Release，提示可更新版本；可下载 zip 并在模块行内查看实时进度、随时取消，下载完成后按钮直接变成「打开 / 删除」。临时文件集中管理、支持一键清空（两级确认）。安装包只存 app 私有目录，不写入 `/data/adb`；**不提供刷写功能**。
- **诊断**：内存日志（上限 600 条）、崩溃报告、一键复制完整诊断报告便于反馈问题。
- **模块下载**：首页进入后先显示分类（Root 管理器、Zygisk、LSPosed、隐藏应用列表、元模块、TEE 模拟、广告屏蔽），点分类才展开该分类下的模块列表，组内按名称逐字母排序；每项联网查询 GitHub 最新 Release，显示版本号与安装包大小，下载按钮在行最右，支持实时进度、取消与「打开」。顶部显示索引进度，首次打开等索引就绪后才可进入分类；独立的「下载管理」页可打开 / 删除 / 一键清空。索引来自仓库的 `modules/index.json`，并内置一份兜底副本。
- **dex2oat 编译**：开始按钮置顶并附当前设置摘要；选择范围（用户应用 / 全部应用，chip 内显示待编译数量，随范围变化）、模式（快速 / 热点 / 全量，带矢量图标）与线程（系统档位 / 2 / 4 / 8 / 全开，带矢量图标）；编译队列与进度持久化，中断后可从剩余应用继续，完成后展示每个失败应用的具体原因。编译期间运行前台服务通知，并提供通知权限、电池优化白名单与无障碍保活的引导；通知会尝试写入小米超级岛 / ColorOS 流体云的 extras（系统不支持时静默退化）。需要 Root。
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
├── core/        # Root 检测、Shell、模块扫描/加载/更新、模块目录、dex2oat、Keybox、备份、密钥认证
├── data/        # 网络请求、设置、在线索引数据源、数据模型、安装预览模型
├── ui/          # Compose 页面（首页、环境、模块、模块更新、模块下载、下载管理、dex2oat、密钥库、预览、备份、密钥检测、目标、诊断、设置、关于）

modules/index.json  # 模块下载页的远程索引（同时打包进 APK 作兜底）
├── AppViewModel.kt
├── MainActivity.kt / RootToolboxApp.kt
└── RootToolboxApplication.kt
```

## 密钥检测（Key Attestation）

向设备要一把**自己的**认证密钥：在 `AndroidKeyStore` 里生成 EC P-256 密钥，`attestationChallenge` 用当前时间字符串（与参考实现一致），优先 StrongBox，取回证书链后按参考实现的方式解析：

1. 把证书链反转成「根 → 叶」，逐级验签，根证书自签名校验；
2. 逐张检查有效期与 CA 约束（中间证书必须是 CA，终端证书不能是 CA）；
3. 取根证书公钥，与已知信任锚逐一比对：**Google 硬件认证根**、**Google 远程密钥配置根（RKP）**、AOSP 软件根（EC / RSA）、三星 Knox 三把 SAK 根，最后是系统资源 `android:array/vendor_required_attestation_certificates` 里的 OEM 根；
4. 解析叶证书的 KeyDescription：`attestationChallenge` 与本次写入的字符串比对，`RootOfTrust` 取 `verifiedBootState` / `deviceLocked` / `verifiedBootKey` / `verifiedBootHash`，并检查 AuthorizationList 标签顺序是否被改写（顺序错乱视为篡改）；
5. 用叶证书序列号（十进制）比对 **Google 官方吊销列表** `https://android.googleapis.com/attestation/status` 的本地缓存：
   - **缓存优先**：有缓存就立即判定，检测不会因为联网而变慢；
   - **24 小时过期**：Google 响应头就是 `max-age=86400`，过期后检测页会提示「建议获取最新吊销列表」；
   - **首次运行**（没有缓存）时才在检测过程中联网拉一次；失败则吊销状态显示「未校验」，不假装通过；
   - 设置与检测页都提供「获取最新吊销列表」，会先提示国内网络可能需要 VPN，失败时保留原缓存；获取过程中可点 × 取消。

页面结构对齐参考实现：顶部是根证书等级（例如「谷歌远程密钥配置」），紧接着是引导加载程序状态（已锁定 / 已解锁 / 信任根由最终用户设置 / 状态未知），再往下才是证书链、可信执行环境、构建指纹、信任根、认证质询。首页卡片的徽标另外给出 **有效 / 无效 / 未校验**：只有「Google 硬件根或 Google 远程密钥根 + 证书链有效 + 质询匹配 + 未被吊销 + 密钥在 TEE / StrongBox 中生成」才算有效；吊销状态还没拿到时显示「未校验」，不会当成有效。

整页**不读取 keybox.xml、不依赖 Root**；证书不上传，只有获取吊销列表需要联网（结果缓存在本机）。检测时机：只在**冷启动**（`viewModel.start()`）或用户手动点「检测 / 重新检测」时执行；切换后台再回来不会重新检测，Root 状态同理。

> 参考实现：[vvb2060/KeyAttestation](https://github.com/vvb2060/KeyAttestation)。本项目的判定、证书解析与 UI 为自行实现，仅内置其公开的根公钥；吊销列表不再随 APK 打包，改为联网获取并缓存。

## 模块更新检查

扫描每个模块 `module.prop` 里的 `updateJson`（Magisk 模块更新约定），并尝试从该地址或 `zipUrl` 推断 GitHub 仓库、对照最新 Release 的 tag 与 zip 资源。两个来源任一可用即可下载；只有 `versionCode` 变大或版本字符串变化时才提示「有新版本」。

- **不会自动联网**：进入页面不检查，必须点「检查更新」。检查期间先把扫到的模块名摆进列表，只有版本号那处文字用组件库的 `MochiListRow` value 槽位（`ListBlurText`）做模糊替换；标题、状态标签、按钮与进度全程清晰，不再整块模糊。结果返回后一次性替换。
- **排序**：有更新的模块排最前，未提供 `updateJson` 的模块沉到最底；模块行只显示当前/最新版本与紧凑状态标签，不展示更新说明。
- **下载进度内联且可取消**：进度环与进度条显示在对应模块自己的行里，不单设「下载中」板块；下载中可点「取消下载」，取消会 cancel 协程并断开连接（阻塞中的 `read` 立即返回），不落半成品文件、不记网络失败日志、不误报「下载失败」。下载完成后，原来的「下载新版本」按钮直接变成「打开 / 删除」两个按钮。
- 下载的 zip 保存在 app 私有目录 `filesDir/module-updates/`，不写 `/data/adb`，不需要额外权限。「打开」经 `FileProvider` 以 `content://` 临时授权给外部应用查看。
- 临时文件可查看、单个删除、一键清空；删除与清空都有二次确认。**本应用不提供模块刷写功能**，如需安装请自行在模块管理器中操作。

> 该功能是「检查 + 下载 + 管理安装包」，不修改系统分区、不调用模块管理器，也不保证第三方 `updateJson` 的可用性与真实性。

## 模块下载

打开后先是一级分类页，顶部显示索引来源与加载进度；索引就绪前分类不可进入。点分类进入二级页，列出该分类下的模块（紧凑行、下载按钮在最右）。

索引文件在仓库的 `modules/index.json`，应用按以下顺序取用：本地缓存（24 小时内）→ `raw.githubusercontent.com` → `cdn.jsdelivr.net` → APK 内置副本。改动索引只需改这个文件，不用发新版；条目格式：

```json
{ "id": "magisk", "name": "Magisk", "category": "root",
  "repo": "topjohnwu/Magisk", "description": "经典系统级 Root 与模块管理器" }
```

版本号通过 GitHub `releases` 列表取最新**正式版**（无正式版才退回预发布），结果缓存 6 小时以减少 API 限流；资产优先取最大的 `.apk`（排除 debug 命名产物），没有则取最大的 `.zip`。下载文件保存在 `files/module-downloads/`，通过 FileProvider 以 `content://` 交给系统安装器或文件查看器打开。

## dex2oat 编译

通过 `cmd package compile -m quicken|speed-profile|speed -f <pkg>` 逐个应用编译（**不带 `--user`**：AOSP 的 `cmd package compile` 不支持该参数，传了会报 `unknown option` 导致每个应用都失败，已去掉）。编译模式（快速 / 热点 / 全量）与线程（系统档位 / 2 / 4 / 8 / 全开）用组件库的分档滑条选择，开始按钮置顶并附当前设置摘要。线程数通过临时 `setprop dalvik.vm.dex2oat-threads <n>` 生效，结束后还原为原值（部分 ROM 会忽略该属性）。任务队列、已完成与失败的包写入 `files/dexopt/job.json`，中断后页面会提示「上次任务未完成」并可从剩余应用继续，完成后展开每个失败应用的具体原因。

编译期间启动前台服务持有进度通知（开始编译即弹出）；通知同时写入小米超级岛 / 焦点通知的 `miui.focus.param`（结构参考小米官方开发指南与 HyperIslandApi，含 `aodTitle` / `param_island` / 进度）与 OPPO 流体云的 `com.oplus.notification.fluid.*` extras 尝试上岛/上云，系统不支持时会静默忽略。无障碍保活第一次由用户手动开启，之后若被系统关闭且已有 Root，会用 Root 自动写回 `Settings.Secure` 重新开启；设置页也可手动跳转通知权限、电池优化白名单与无障碍服务。

## 已知限制与风险

- **模块下载依赖 GitHub API**：未登录时每小时 60 次，列表第一次刷新会查询全部条目；结果缓存 6 小时，触发限流时页面会提示稍后再试。
- **dex2oat 需要 Root**：`cmd package compile` 与 `setprop` 都要 root；线程控制是否生效取决于 ROM，编译失败会在任务里逐条记录。
- **密钥检测依赖厂商密钥认证**：设备需要有可用的 TEE / StrongBox 与 Google 认证根；模拟器、部分无 GMS 认证的设备或 ROM 只能得到「未知根证书 / AOSP 软件认证根证书」，首页会显示「无效」并说明原因。
- **吊销校验依赖缓存**：安装后第一次检测需要联网拉一次 Google 列表（国内网络可能需要 VPN）；之后 24 小时内直接用缓存。超过 24 小时会提示获取最新列表，但不会阻止查看结论，只是可能漏掉新吊销的密钥。完全没有缓存且联网失败时，徽标显示「未校验」。
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
