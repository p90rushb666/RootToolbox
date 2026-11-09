package dev.mochi.rootbox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import dev.mochi.rootbox.data.AppSettings
import dev.mochi.rootbox.data.DarkMode
import dev.mochi.rootbox.data.GreenRating
import dev.mochi.rootbox.data.KeyboxItem
import dev.mochi.rootbox.data.KeyboxStatus
import ui.feice.mochi.basic.Text
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Maps the stored preference onto the library's theme mode. */
fun AppSettings.colorSchemeMode(): ColorSchemeMode = when (darkMode) {
    DarkMode.SYSTEM -> if (monet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    DarkMode.LIGHT -> if (monet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
    DarkMode.DARK -> if (monet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

fun formatDate(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return "未知"
    return synchronized(dateFormat) { dateFormat.format(Date(epochMillis)) }
}

fun formatDateTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return "未知"
    return synchronized(dateTimeFormat) { dateTimeFormat.format(Date(epochMillis)) }
}

fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return "未知"
    val diff = System.currentTimeMillis() - epochMillis
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> formatDate(epochMillis)
    }
}

/**
 * Pull-to-refresh for pages whose content is shorter than the screen: Mochi's own refresh reads the
 * overscroll effect, which never engages when the list cannot scroll. This container takes the drag
 * over in exactly that case; long pages keep using the library behaviour (pass null to the scaffold).
 */
@Composable
fun ShortContentPullRefresh(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val triggerPx = with(density) { 88.dp.toPx() }
    val holdPx = with(density) { 52.dp.toPx() }
    val maxPx = with(density) { 180.dp.toPx() }
    val slopPx = with(density) { 10.dp.toPx() }
    val pullState = remember { mutableFloatStateOf(0f) }

    // Paint the page background behind the content: otherwise the translated page uncovers the
    // window's white background and the pull looks like a torn sheet.
    Box(
        modifier
            .fillMaxSize()
            .background(MochiDefaults.groupedBackground()),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 用 offset 而不是 graphicsLayer：后者会把整页塞进一个离屏图层，
                // 长列表滚动时每帧都要重录+合成，是诊断/密钥库滑动卡顿的主因。
                .offset { IntOffset(0, pullState.floatValue.roundToInt()) }
                .pointerInput(enabled, refreshing) {
                    if (!enabled || refreshing) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pulling = false
                        var dySum = 0f
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (pullState.floatValue >= triggerPx) {
                                    scope.launch {
                                        Animatable(pullState.floatValue).animateTo(
                                            holdPx,
                                            spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { pullState.floatValue = value }
                                        onRefresh()
                                        Animatable(pullState.floatValue).animateTo(
                                            0f,
                                            spring(stiffness = Spring.StiffnessMediumLow),
                                        ) { pullState.floatValue = value }
                                    }
                                } else {
                                    scope.launch {
                                        Animatable(pullState.floatValue).animateTo(0f, spring()) {
                                            pullState.floatValue = value
                                        }
                                    }
                                }
                                break
                            }
                            dySum += change.position.y - lastY
                            lastY = change.position.y
                            if (!pulling) {
                                pulling = change.position.y - down.position.y > slopPx
                            }
                            if (pulling) {
                                pullState.floatValue = (dySum * 0.55f).coerceIn(0f, maxPx)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            content()
        }

        val pull = pullState.floatValue
        if (pull > 1f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    // Keep the spinner below the status bar / camera cutout: it belongs to the gap
                    // that opens between the system bar and the pulled-down page.
                    .statusBarsPadding()
                    .offset(y = with(density) { (pull / 2f).toDp() } - 12.dp),
            ) {
                // iOS 风格的辐条：下拉时按进度一根根显出，刷新时自己转。
                MochiActivityIndicator(
                    size = 24.dp,
                    reveal = if (refreshing) null else ({ (pull / triggerPx).coerceIn(0f, 1f) }),
                )
            }
        }
    }
}

@Composable
fun MochiProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val scheme = MochiTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(scheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.primary),
        )
    }
}

/**
 * 状态胶囊。外观和 [ui.feice.mochi.list.MochiListStatusChip] 一致，但**不做同心圆角**——
 * 后者会每帧读取父卡片坐标，长列表里一屏十几个胶囊会让滚动掉帧。
 */
@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(containerColor, MochiCapsule)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MochiTheme.textStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun GreenChip(rating: GreenRating) {
    val scheme = MochiTheme.colorScheme
    when (rating) {
        GreenRating.THREE_GREEN -> StatusPill("三绿", scheme.primary, scheme.onPrimary)
        GreenRating.NOT_THREE -> StatusPill("非三绿", scheme.secondaryContainer, scheme.onSecondaryContainer)
        GreenRating.UNVERIFIED -> StatusPill("三绿未验证", scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(item: KeyboxItem) {
    val scheme = MochiTheme.colorScheme
    when (item.status) {
        KeyboxStatus.USABLE -> StatusPill(item.statusLabel, scheme.primaryContainer, scheme.onPrimaryContainer)
        KeyboxStatus.EXPIRED -> StatusPill(item.statusLabel, scheme.errorContainer, scheme.onErrorContainer)
        KeyboxStatus.REVOKED -> StatusPill(item.statusLabel, scheme.error, scheme.onError)
        KeyboxStatus.UNKNOWN -> StatusPill(item.statusLabel, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

fun shortPath(path: String, max: Int = 38): String =
    if (path.length <= max) path else "…" + path.takeLast(max - 1)

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "—"
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
}
