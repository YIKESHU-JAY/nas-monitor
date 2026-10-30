package io.github.nasmonitor.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import io.github.nasmonitor.app.DiskInfo

@Composable
fun FrostCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isGlassTheme()) {
        val shape = RoundedCornerShape(24.dp)
        val base = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
        Column(
            modifier = base
                .liquidGlass(shape)
                .padding(16.dp)
        ) { content() }
        return
    }
    // 非玻璃普通卡保持 8%-12% 透出；elevation 会在半透明表面上叠出白矩形。
    Card(
        modifier = (if (onClick != null) modifier.clickable(onClick = onClick) else modifier)
            .clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = NON_GLASS_CARD_ELEVATION),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(Track, Color.Transparent, Track))
        )
    ) { Column(Modifier.padding(16.dp), content = content) }
}

fun dedupDisks(disks: List<DiskInfo>?): List<DiskInfo> {
    if (disks == null) return emptyList()
    val seen = linkedSetOf<String>()
    val result = mutableListOf<DiskInfo>()
    for (d in disks.filter { it.mount.matches(Regex("^/vol[0-9]+$")) }) {
        if (seen.add(d.mount)) result.add(d)
    }
    for (d in disks.filter { it.mount.matches(Regex("^/host-root/vol[0-9]+$")) }) {
        val mount = d.mount.removePrefix("/host-root")
        if (seen.add(mount)) result.add(d.copy(mount = mount))
    }
    return result.sortedBy { it.mount }
}

fun rankColor(value: Double) = usageColor(value)

@Composable
fun MetricCard(value: String, label: String, color: Color = Ink) {
    FrostCard {
        androidx.compose.material3.Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        androidx.compose.material3.Text(label, fontSize = 11.sp, color = Muted)
    }
}

// ── 按钮分级（第二波新增）──

/** 主要操作按钮：实心 accent、圆角 12dp、阴影 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentFill,
            contentColor = OnAccent
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        M3Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 次要操作按钮：描边 accent、透明背景 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Blue
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(listOf(Blue.copy(alpha = 0.6f), Blue.copy(alpha = 0.3f)))
        )
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        M3Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 幽灵按钮：纯文字、无背景，用于最轻量操作 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp)
    ) {
        M3Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Blue)
    }
}

/** Shared page header. System insets are supplied by the root scaffold. */
@Composable
fun AppPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = if (onBack == null) 18.dp else 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Ink)
            }
        }
        Column(Modifier.weight(1f)) {
            M3Text(title, fontSize = if (onBack == null) TextH1 else 20.sp,
                fontWeight = FontWeight.Bold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { M3Text(it, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

@Composable
fun LoadFeedback(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        M3Text(message, color = Muted, fontSize = 13.sp)
        if (onRetry != null) TextButton(onClick = onRetry) { M3Text("重试") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactConfirmationDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    containerColor: Color = OverlaySurface
) {
    Dialog(onDismissRequest = onDismissRequest) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(primary = Blue, surface = containerColor,
                onSurface = Ink, onSurfaceVariant = Muted),
            typography = MaterialTheme.typography.copy(labelLarge = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
        ) {
            Surface(modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                shape = RoundedCornerShape(18.dp), color = containerColor, contentColor = Ink) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold, color = Ink)) { title() }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp,
                            color = Muted)) { text() }
                    }
                    Spacer(Modifier.height(4.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, status: String = "info", modifier: Modifier = Modifier) {
    val surface = when (status) {
        "ok", "healthy" -> StatusHealthySurface
        "warn", "attention" -> StatusAttentionSurface
        "err", "critical" -> StatusCriticalSurface
        else -> StatusSummarySurface
    }
    val ink = when (status) {
        "ok", "healthy" -> StatusHealthyInk
        "warn", "attention" -> StatusAttentionInk
        "err", "critical" -> StatusCriticalInk
        else -> StatusSummaryInk
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = surface) {
        M3Text(text, color = ink, fontSize = TextTiny, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
fun MaskedText(value: String, label: String? = null, modifier: Modifier = Modifier, semanticLabel: String = label ?: "敏感信息") {
    var visible by rememberSaveable(value) { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) M3Text(label, color = Muted, fontSize = TextCaption)
        M3Text(if (visible) value else "•••.•••.•••.•••", color = Ink, fontSize = TextCaption,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        GhostButton(if (visible) "隐藏" else "显示", { visible = !visible }, modifier = Modifier.semantics {
            contentDescription = "${if (visible) "隐藏" else "显示"}$semanticLabel"
            stateDescription = if (visible) "已显示" else "已隐藏"
        })
    }
}

/** Label-sized selection surface inside a full-sized touch target. */
@Composable
fun ContentSizedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                M3Text(
                    text = label,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (active) BlueSoft else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (active) OnAccentSoft else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Scrollable trailing space; the page itself draws behind the floating bottom bar. */
val LocalFloatingNavigationPadding = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** Android elevation shadows show through translucent surfaces as dark cutout shapes. */
@Composable
fun MonitorCardElevation(defaultElevation: androidx.compose.ui.unit.Dp = 1.dp): CardElevation =
    if (isGlassTheme()) CardDefaults.cardElevation(
        defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp,
        hoveredElevation = 0.dp, draggedElevation = 0.dp, disabledElevation = 0.dp
    ) else CardDefaults.cardElevation(defaultElevation = defaultElevation)
