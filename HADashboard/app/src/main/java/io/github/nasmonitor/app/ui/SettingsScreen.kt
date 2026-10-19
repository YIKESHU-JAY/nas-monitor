package io.github.nasmonitor.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nasmonitor.app.DisplayPreferences
import io.github.nasmonitor.app.FontScaleOption
import io.github.nasmonitor.app.PreferenceHelper
import io.github.nasmonitor.app.ServerListStyle
import io.github.nasmonitor.app.TaskFieldPreset
import io.github.nasmonitor.app.TaskListDensity


@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun SettingsScreen(onChangeToken: () -> Unit = {}, onManageServers: () -> Unit = {}) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var refreshSeconds by remember { mutableStateOf(PreferenceHelper.getRefreshInterval(context)) }
    var theme by remember { mutableStateOf(PreferenceHelper.getTheme(context)) }
    var accent by remember { mutableStateOf(PreferenceHelper.getAccentColor(context)) }
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(theme, systemDark) {
        applyTheme(if (theme == "system") (if (systemDark) "dark" else "light") else theme)
        setAccentColor(accent)
    }
    val normalizedQuery = query.trim().lowercase()
    fun matches(keywords: String) = normalizedQuery.isBlank() || keywords.lowercase().contains(normalizedQuery)
    val showOverview = matches("刷新 总览 服务器 间隔")
    val showAppearance = matches("外观 主题 主题色 字号 深色 浅色 紫夜 玻璃 极光 日落")
    val showDisplay = matches("列表 显示 视觉卡片 简洁列表 任务 密度 字段 预设 刷新 最多")
    val showManage = matches("服务器 管理 添加 删除 SSH 凭证")
    val showToken = matches("访问 口令 权限 令牌 更换")
    val matchedGroups = listOf(showOverview, showAppearance, showDisplay, showManage, showToken).count { it }
    Scaffold(containerColor = PageBackground) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = LocalFloatingNavigationPadding.current)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("搜索设置：字号、刷新、列表…", fontSize = 13.sp, color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { query = "" }) { Text("清空", fontSize = 12.sp, color = Blue) }
                    }
                }
            )
            if (normalizedQuery.isNotBlank()) {
                Text("找到 $matchedGroups 个设置分组", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (showOverview) {
            LiquidAwareCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("服务器总览刷新", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10, 0).forEach { sec ->
                            AppFilterChip(
                                selected = refreshSeconds == sec,
                                onClick = { refreshSeconds = sec; PreferenceHelper.setRefreshInterval(context, sec) },
                                label = if (sec > 0) "$sec 秒" else "手动"
                            )
                        }
                    }
                    Text("仅影响服务器总览；其他页面按各自策略刷新。", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            }
            if (showAppearance) {
            LiquidAwareCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("外观主题", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "system" to "跟随系统",
                            "light" to "浅色",
                            "dark" to "深色",
                            "purple" to "紫夜",
                            "glass" to "玻璃",
                            "aurora" to "极光",
                            "sunset" to "日落"
                        ).forEach { (key, label) ->
                            AppFilterChip(
                                selected = theme == key,
                                onClick = {
                                    theme = key
                                    PreferenceHelper.setTheme(context, key)
                                    applyTheme(if (key == "system") (if (systemDark) "dark" else "light") else key)
                                },
                                label = label,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Divider(color = Track, thickness = 1.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("主题色", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentOptions.forEach { (name, color) ->
                            val selected = color.hex().equals(accent, ignoreCase = true)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected = selected, role = Role.RadioButton) {
                                    accent = color.hex()
                                    PreferenceHelper.setAccentColor(context, color.hex())
                                    setAccentColor(color.hex())
                                }.padding(vertical = 8.dp)
                            ) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(color)
                                    .border(if (selected) 2.dp else 1.dp, if (selected) Ink else Track, CircleShape))
                                Spacer(Modifier.height(4.dp))
                                Text(name, fontSize = 10.sp, color = if (selected) Blue else Muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Divider(color = Track, thickness = 1.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("外观与显示", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("字号会作用于全 App 的文字，立即生效。", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FontScaleOption.entries.forEach { option ->
                            AppFilterChip(
                                selected = DisplayPreferences.fontScaleOption == option,
                                onClick = { DisplayPreferences.setFontScale(context, option) },
                                label = option.label,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            }
            if (showDisplay) {
            LiquidAwareCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("列表与显示", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(14.dp))
                    SettingLabel("服务器列表")
                    Spacer(Modifier.height(7.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ServerListStyle.entries.forEach { style ->
                            AppFilterChip(
                                selected = DisplayPreferences.serverListStyle == style,
                                onClick = { DisplayPreferences.setServerListStyle(context, style) },
                                label = style.label,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        if (DisplayPreferences.serverListStyle == ServerListStyle.VISUAL) "保留指标卡与核心服务，信息完整。" else "单行显示编号、状态、CPU、内存与上下行，适合多服务器。",
                        color = Muted, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(14.dp)); Divider(color = Track, thickness = 1.dp); Spacer(Modifier.height(14.dp))
                    SettingLabel("任务列表密度")
                    Spacer(Modifier.height(7.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskListDensity.entries.forEach { density ->
                            AppFilterChip(
                                selected = DisplayPreferences.taskDensity == density,
                                onClick = { DisplayPreferences.setTaskDensity(context, density) },
                                label = density.label,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp)); Divider(color = Track, thickness = 1.dp); Spacer(Modifier.height(14.dp))
                    SettingLabel("任务字段预设")
                    Spacer(Modifier.height(7.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskFieldPreset.entries.forEach { preset ->
                            AppFilterChip(
                                selected = DisplayPreferences.taskFieldPreset == preset,
                                onClick = { DisplayPreferences.setTaskFieldPreset(context, preset) },
                                label = preset.label,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(DisplayPreferences.taskFieldPreset.description, color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(14.dp)); Divider(color = Track, thickness = 1.dp); Spacer(Modifier.height(14.dp))
                    SettingLabel("任务页刷新")
                    Spacer(Modifier.height(7.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(-1 to "跟随总览", 5 to "5 秒", 10 to "10 秒", 30 to "30 秒", 0 to "手动").forEach { (seconds, label) ->
                            AppFilterChip(
                                selected = DisplayPreferences.taskRefreshSeconds == seconds,
                                onClick = { DisplayPreferences.setTaskRefreshSeconds(context, seconds) },
                                label = label,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp)); Divider(color = Track, thickness = 1.dp); Spacer(Modifier.height(14.dp))
                    SettingLabel("最多显示任务")
                    Spacer(Modifier.height(7.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "不限", 50 to "50 条", 100 to "100 条", 200 to "200 条").forEach { (count, label) ->
                            AppFilterChip(
                                selected = DisplayPreferences.taskMaxCount == count,
                                onClick = { DisplayPreferences.setTaskMaxCount(context, count) },
                                label = label,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            }
            if (showManage) {
            LiquidAwareCard(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onManageServers),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(CardWhite),
                elevation = MonitorCardElevation(1.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("服务器管理", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("添加、删除服务器与配置 SSH 连接", fontSize = 12.sp, color = Muted)
                    }
                    Text("›", fontSize = 24.sp, color = Muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            }
            if (showToken) {
            LiquidAwareCard(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onChangeToken),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(CardWhite),
                elevation = MonitorCardElevation(1.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("更换访问口令", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("重新验证 Monitor 访问权限", fontSize = 12.sp, color = Muted)
                    }
                    Text("›", fontSize = 24.sp, color = Muted)
                }
            }
            }
            if (normalizedQuery.isNotBlank() && matchedGroups == 0) {
                LoadFeedback("没有匹配的设置项，试试“字号”“刷新”或“列表”。")
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = fontSize, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = BlueSoft,
            selectedLabelColor = Blue,
            containerColor = if (isGlassTheme()) GlassInnerFill else CardTint,
            labelColor = Muted
        )
    )
}
