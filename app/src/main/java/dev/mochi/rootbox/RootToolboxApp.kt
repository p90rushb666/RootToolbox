package dev.mochi.rootbox

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.mochi.rootbox.ui.AboutScreen
import dev.mochi.rootbox.ui.BackupScreen
import dev.mochi.rootbox.ui.DexoptScreen
import dev.mochi.rootbox.ui.DiagnosticsScreen
import dev.mochi.rootbox.ui.EnvironmentScreen
import dev.mochi.rootbox.ui.HomeScreen
import dev.mochi.rootbox.ui.InstallPreviewScreen
import dev.mochi.rootbox.ui.AttestationScreen
import dev.mochi.rootbox.ui.KeysScreen
import dev.mochi.rootbox.ui.ModuleScreen
import dev.mochi.rootbox.ui.ModuleCatalogScreen
import dev.mochi.rootbox.ui.ModuleDownloadsScreen
import dev.mochi.rootbox.ui.ModuleUpdateScreen
import dev.mochi.rootbox.ui.SettingsScreen
import dev.mochi.rootbox.ui.TargetScreen
import dev.mochi.rootbox.ui.colorSchemeMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import ui.feice.mochi.basic.MochiTabBar
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.MochiToastHost
import ui.feice.mochi.basic.rememberMochiToastState
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.effect.content.MochiBlurTransition
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.icons.MochiIconStyle
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.rememberMochiNavigator
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController

private const val RouteHome = "home"
private const val RouteEnvironment = "environment"
private const val RouteModule = "module"
private const val RouteTarget = "target"
private const val RouteDiagnostics = "diagnostics"
private const val RouteAbout = "about"
private const val RouteSettings = "settings"
private const val RouteBackup = "backup"
private const val RouteAttestation = "attestation"
private const val RouteModuleUpdates = "module-updates"
private const val RouteModuleCatalog = "module-catalog"
private const val RouteModuleDownloads = "module-downloads"
private const val RouteDexopt = "dexopt"
private const val RouteKeys = "keys"
private const val RoutePreview = "preview"

// 打开式转场（mochiNavSource）的 source key，需与 HomeScreen 卡片上的标记一致。
private const val SourceEnvironment = "home/environment"
private const val SourceModule = "home/module"
private const val SourceDiagnostics = "home/diagnostics"
private const val SourceAttestation = "home/attestation"
private const val SourceModuleUpdates = "home/module-updates"
private const val SourceModuleCatalog = "home/module-catalog"
private const val SourceDexopt = "home/dexopt"

@Composable
fun RootToolboxApp(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val controller = remember { ThemeController(settings.colorSchemeMode()) }
    val libraryDefaultKeyColor = remember { controller.keyColor }
    LaunchedEffect(settings.darkMode, settings.monet, settings.keyColor) {
        controller.colorSchemeMode = settings.colorSchemeMode()
        controller.keyColor = settings.keyColor?.let { Color(it) } ?: libraryDefaultKeyColor
    }

    MochiTheme(controller = controller) {
        val toast = rememberMochiToastState()
        val context = LocalContext.current
        val state by viewModel.state.collectAsState()

        LaunchedEffect(Unit) {
            // 等首帧画完再开始 Root 探测 / 模块扫描，打开时先出界面再干活。
            withFrameNanos { }
            delay(120)
            viewModel.start()
        }
        LaunchedEffect(state.message) {
            val message = state.message
            if (message != null) {
                toast.show(message)
                viewModel.consumeMessage()
            }
        }

        // 刻意不监听 ON_RESUME：按需求，切换后台再回来不重新检测 Root / 密钥，
        // 只有冷启动（viewModel.start()）或用户手动刷新才检测。

        val showToast: (String) -> Unit = { message -> toast.show(message) }
        val openBrowser: (String) -> Unit = { url ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        val selectedTab = rememberSaveable { mutableIntStateOf(0) }
        val tabStateHolder = rememberSaveableStateHolder()
        // demo TabBarPage 里的「融球残影」开关：fuseRadius > 0 时选中块后面会拖出一颗
        // 水滴并在追上后融回。material = null 锁定纯色标签栏——玻璃分支不画这颗水滴。
        val barBehavior = remember {
            MochiTabBarDefaults.behavior(gel = 0.6f, fuseRadius = 22.dp)
        }
        val tabItems = remember {
            persistentListOf(
                MochiTabItem(
                    label = "首页",
                    selectedIcon = { tint -> MochiIonicon("home", style = MochiIconStyle.Filled, tint = tint) },
                    icon = { tint -> MochiIonicon("home", tint = tint) },
                ),
                MochiTabItem(
                    label = "密钥库",
                    selectedIcon = { tint -> MochiIonicon("key", style = MochiIconStyle.Filled, tint = tint) },
                    icon = { tint -> MochiIonicon("key", tint = tint) },
                ),
                MochiTabItem(
                    label = "设置",
                    selectedIcon = { tint -> MochiIonicon("settings", style = MochiIconStyle.Filled, tint = tint) },
                    icon = { tint -> MochiIonicon("settings", tint = tint) },
                ),
            )
        }

        ProvideMochiOverscroll {
            // 用公开的 MochiTabBar 自己搭标签页，而不是 MochiTabScaffold：
            // 后者会把整页录进 backdrop 图层并套一层同层转场，长列表滚动时每帧都要重录，实测掉帧严重。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MochiDefaults.groupedBackground()),
            ) {
                CompositionLocalProvider(LocalMochiBottomBarInset provides barBehavior.height) {
                    // 组件库的模糊替换转场，配 blur = 0 / scale = 1 退化成纯淡入淡出：
                    // 半径为 0 时 mochiBlur 不建离屏图层，不会回到 MochiTabScaffold 那种整页录制的掉帧路径。
                    MochiBlurCrossfade(
                        targetState = selectedTab.intValue,
                        transition = MochiBlurTransition(blur = 0.dp, scale = 1f, slide = 0f),
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        tabStateHolder.SaveableStateProvider(tab) {
                            Box(Modifier.fillMaxSize()) {
                                when (tab) {
                                    0 -> HomeTab(state, settings, viewModel, showToast)
                                    1 -> KeysTab(settings, state, viewModel, showToast, openBrowser)
                                    else -> SettingsTab(
                                        settings, state, viewModel, settings.predictiveBack, showToast,
                                    )
                                }
                            }
                        }
                    }
                }

                MochiTabBar(
                    selectedIndex = selectedTab.intValue,
                    onSelect = { selectedTab.intValue = it },
                    items = tabItems,
                    behavior = barBehavior,
                    material = null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            MochiToastHost(toast)
        }
    }
}

@Composable
private fun HomeTab(
    state: AppState,
    settings: dev.mochi.rootbox.data.AppSettings,
    viewModel: AppViewModel,
    showToast: (String) -> Unit,
) {
    val nav = rememberMochiNavigator(RouteHome)
    MochiNavHost(nav, Modifier.fillMaxSize(), predictiveBack = settings.predictiveBack) { route ->
        val back: @Composable () -> Unit = { MochiBackButton(history = ::titleFor) }
        when (route) {
            RouteHome -> HomeScreen(
                state = state,
                viewModel = viewModel,
                onOpenEnvironment = { nav.push(RouteEnvironment, from = SourceEnvironment) },
                onOpenModule = { nav.push(RouteModule, from = SourceModule) },
                onOpenDiagnostics = { nav.push(RouteDiagnostics, from = SourceDiagnostics) },
                onOpenAttestation = { nav.push(RouteAttestation, from = SourceAttestation) },
                onOpenModuleUpdates = { nav.push(RouteModuleUpdates, from = SourceModuleUpdates) },
                onOpenModuleCatalog = { nav.push(RouteModuleCatalog, from = SourceModuleCatalog) },
                onOpenDexopt = { nav.push(RouteDexopt, from = SourceDexopt) },
                onOpenAbout = { nav.push(RouteAbout) },
            )

            RouteEnvironment -> EnvironmentScreen(state, viewModel, back)
            RouteModule -> ModuleScreen(
                state = state,
                viewModel = viewModel,
                onOpenTargets = { nav.push(RouteTarget) },
                onOpenBackups = { nav.push(RouteBackup) },
                navigationIcon = back,
                showToast = showToast,
            )
            RouteBackup -> BackupScreen(state, viewModel, back, showToast)
            RouteAttestation -> AttestationScreen(state, settings, viewModel, back, showToast)
            RouteModuleUpdates -> ModuleUpdateScreen(state, viewModel, back, showToast)
            RouteModuleCatalog -> ModuleCatalogScreen(
                state = state,
                viewModel = viewModel,
                navigationIcon = back,
                onOpenDownloads = { nav.push(RouteModuleDownloads) },
                showToast = showToast,
            )
            RouteModuleDownloads -> ModuleDownloadsScreen(state, viewModel, back, showToast)
            RouteDexopt -> DexoptScreen(state, viewModel, back, showToast)
            RouteTarget -> TargetScreen(state, viewModel, back, showToast)
            RouteDiagnostics -> DiagnosticsScreen(state, viewModel, back, showToast)
            RouteAbout -> AboutScreen(back)
        }
    }
}

@Composable
private fun KeysTab(
    settings: dev.mochi.rootbox.data.AppSettings,
    state: AppState,
    viewModel: AppViewModel,
    showToast: (String) -> Unit,
    openBrowser: (String) -> Unit,
) {
    val nav = rememberMochiNavigator(RouteKeys)
    MochiNavHost(nav, Modifier.fillMaxSize(), predictiveBack = settings.predictiveBack) { route ->
        val back: @Composable () -> Unit = { MochiBackButton(history = ::titleFor) }
        when (route) {
            RoutePreview -> InstallPreviewScreen(
                state = state,
                settings = settings,
                viewModel = viewModel,
                navigationIcon = back,
                onDone = { nav.pop() },
                showToast = showToast,
            )
            else -> KeysScreen(
                state = state,
                viewModel = viewModel,
                navigationIcon = null,
                showToast = showToast,
                openBrowser = openBrowser,
                showGreenBadge = settings.showGreenBadge,
                onOpenPreview = { nav.push(RoutePreview) },
            )
        }
    }
}

@Composable
private fun SettingsTab(
    settings: dev.mochi.rootbox.data.AppSettings,
    state: AppState,
    viewModel: AppViewModel,
    predictiveBack: Boolean,
    showToast: (String) -> Unit,
) {
    val nav = rememberMochiNavigator(RouteSettings)
    MochiNavHost(nav, Modifier.fillMaxSize(), predictiveBack = predictiveBack) { route ->
        val back: @Composable () -> Unit = { MochiBackButton(history = ::titleFor) }
        when (route) {
            RouteSettings -> SettingsScreen(settings, state, viewModel, null, { nav.push(RouteTarget) })
            RouteTarget -> TargetScreen(state, viewModel, back, showToast)
        }
    }
}

private fun titleFor(route: String): String = when (route) {
    RouteHome -> "Root 工具箱"
    RouteEnvironment -> "运行环境"
    RouteModule -> "Tricky Store"
    RouteTarget -> "替换目标"
    RouteDiagnostics -> "诊断"
    RouteAbout -> "关于"
    RouteSettings -> "设置"
    RouteBackup -> "备份中心"
    RouteAttestation -> "密钥检测"
    RouteModuleUpdates -> "模块更新"
    RouteModuleCatalog -> "模块下载"
    RouteModuleDownloads -> "下载管理"
    RouteDexopt -> "dex2oat 编译"
    RouteKeys -> "密钥库"
    RoutePreview -> "安装预览"
    else -> route
}
