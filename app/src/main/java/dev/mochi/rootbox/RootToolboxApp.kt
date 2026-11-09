package dev.mochi.rootbox

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mochi.rootbox.ui.AboutScreen
import dev.mochi.rootbox.ui.DiagnosticsScreen
import dev.mochi.rootbox.ui.EnvironmentScreen
import dev.mochi.rootbox.ui.HomeScreen
import dev.mochi.rootbox.ui.KeysScreen
import dev.mochi.rootbox.ui.ModuleScreen
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
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.icons.MochiIconStyle
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
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

// 打开式转场（mochiNavSource）的 source key，需与 HomeScreen 卡片上的标记一致。
private const val SourceEnvironment = "home/environment"
private const val SourceModule = "home/module"
private const val SourceDiagnostics = "home/diagnostics"

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

        // 每次回到前台都重测一次 Root，避免冷启动时守护进程还没就绪而误判成「受限」。
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppForeground()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

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
        val barBehavior = remember { MochiTabBarDefaults.behavior() }
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
                    tabStateHolder.SaveableStateProvider(selectedTab.intValue) {
                        Box(Modifier.fillMaxSize()) {
                            when (selectedTab.intValue) {
                                0 -> HomeTab(state, viewModel, settings.predictiveBack, showToast)
                                1 -> KeysTab(settings, state, viewModel, showToast, openBrowser)
                                else -> SettingsTab(
                                    settings, state, viewModel, settings.predictiveBack, showToast,
                                )
                            }
                        }
                    }
                }

                MochiTabBar(
                    selectedIndex = selectedTab.intValue,
                    onSelect = { selectedTab.intValue = it },
                    items = tabItems,
                    behavior = barBehavior,
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
    viewModel: AppViewModel,
    predictiveBack: Boolean,
    showToast: (String) -> Unit,
) {
    val nav = rememberMochiNavigator(RouteHome)
    MochiNavHost(nav, Modifier.fillMaxSize(), predictiveBack = predictiveBack) { route ->
        val back: @Composable () -> Unit = { MochiBackButton(history = ::titleFor) }
        when (route) {
            RouteHome -> HomeScreen(
                state = state,
                viewModel = viewModel,
                onOpenEnvironment = { nav.push(RouteEnvironment, from = SourceEnvironment) },
                onOpenModule = { nav.push(RouteModule, from = SourceModule) },
                onOpenDiagnostics = { nav.push(RouteDiagnostics, from = SourceDiagnostics) },
                onOpenAbout = { nav.push(RouteAbout) },
            )

            RouteEnvironment -> EnvironmentScreen(state, viewModel, back)
            RouteModule -> ModuleScreen(state, viewModel, { nav.push(RouteTarget) }, back, showToast)
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
    KeysScreen(
        state = state,
        viewModel = viewModel,
        navigationIcon = null,
        showToast = showToast,
        openBrowser = openBrowser,
        showGreenBadge = settings.showGreenBadge,
    )
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
            RouteSettings -> SettingsScreen(settings, viewModel, null, { nav.push(RouteTarget) })
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
    else -> route
}
