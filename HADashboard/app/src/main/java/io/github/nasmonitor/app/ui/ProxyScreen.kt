package io.github.nasmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import io.github.nasmonitor.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProxyScreen(nav: NavHostController, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lifecycleResumed by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lifecycleResumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { lifecycleResumed = false }
    var overview by remember { mutableStateOf<MihomoOverviewResponse?>(null) }
    var airports by remember { mutableStateOf<MihomoAirportsResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var instanceId by rememberSaveable { mutableStateOf("core") }
    var pendingSelect by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var panelError by remember { mutableStateOf<String?>(null) }
    var delayByNode by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var testingNode by remember { mutableStateOf<String?>(null) }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("概览") }
    var connections by remember { mutableStateOf<MihomoConnectionsResponse?>(null) }
    var rules by remember { mutableStateOf<MihomoRulesResponse?>(null) }
    var configs by remember { mutableStateOf<MihomoConfigs?>(null) }
    var providers by remember { mutableStateOf<MihomoProvidersResponse?>(null) }
    var closingAll by remember { mutableStateOf(false) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var pendingRule by remember { mutableStateOf<MihomoRule?>(null) }
    var configChoice by remember { mutableStateOf<String?>(null) }
    var pendingToggle by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var providerBusy by remember { mutableStateOf<String?>(null) }
    val instance = overview?.instances?.firstOrNull { it.id == instanceId }

    suspend fun load() {
        loading = true
        try {
            overview = ApiClient.getMihomoOverview()
            airports = ApiClient.getMihomoAirports()
            connections = ApiClient.getMihomoConnections(instanceId)
            rules = ApiClient.getMihomoRules(instanceId)
            configs = ApiClient.getMihomoConfigs(instanceId)
            providers = ApiClient.getMihomoProviders(instanceId)
            error = null
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(instanceId) {
        try {
            connections = ApiClient.getMihomoConnections(instanceId)
            rules = ApiClient.getMihomoRules(instanceId)
            configs = ApiClient.getMihomoConfigs(instanceId)
            providers = ApiClient.getMihomoProviders(instanceId)
        } catch (_: Throwable) {
        }
    }
    LaunchedEffect(lifecycleResumed) {
        while (lifecycleResumed) {
            delay(5_000)
            try {
                airports = ApiClient.getMihomoAirports()
                if (instanceId == "core" || instanceId == "v2") {
                    overview = ApiClient.getMihomoOverview()
                }
                if (tab == "连接") connections = ApiClient.getMihomoConnections(instanceId)
            } catch (_: Throwable) {
            }
        }
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { if (!closingAll) confirmCloseAll = false },
            title = { Text("关闭全部连接") },
            text = { Text("将断开 ${instance?.name ?: instanceId} 上所有活动连接，可能中断正在进行的下载或会话。") },
            confirmButton = {
                TextButton(enabled = !closingAll, onClick = {
                    scope.launch {
                        closingAll = true
                        try {
                            ApiClient.closeAllMihomoConnections(instanceId)
                            connections = ApiClient.getMihomoConnections(instanceId)
                        } catch (t: Throwable) {
                            error = "关闭失败：${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            closingAll = false
                            confirmCloseAll = false
                        }
                    }
                }) { Text("确认关闭") }
            },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("取消") } }
        )
    }

    pendingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingRule = null },
            title = { Text(if (rule.disabled) "启用规则" else "禁用规则") },
            text = { Text("${rule.type} ${rule.payload}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            ApiClient.setMihomoRuleDisabled(instanceId, rule.index, !rule.disabled)
                            rules = ApiClient.getMihomoRules(instanceId)
                        } catch (t: Throwable) {
                            error = "规则更新失败：${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            pendingRule = null
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pendingRule = null }) { Text("取消") } }
        )
    }

    configChoice?.let { key ->
        val options = when (key) {
            "mode" -> listOf("rule" to "规则", "global" to "全局", "direct" to "直连")
            else -> listOf("info" to "info", "warning" to "warning", "error" to "error", "debug" to "debug")
        }
        val title = if (key == "mode") "代理模式" else "日志级别"
        AlertDialog(
            onDismissRequest = { configChoice = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    configChoice = null
                                    scope.launch {
                                        try {
                                            ApiClient.setMihomoConfig(instanceId, key, value)
                                            configs = ApiClient.getMihomoConfigs(instanceId)
                                            overview = ApiClient.getMihomoOverview()
                                        } catch (t: Throwable) {
                                            error = "设置失败：${t.message ?: t.javaClass.simpleName}"
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) { Text(label, color = Ink, fontSize = 13.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { configChoice = null }) { Text("取消") } }
        )
    }

    pendingToggle?.let { (key, value) ->
        val label = if (key == "allow-lan") "允许局域网" else "IPv6"
        AlertDialog(
            onDismissRequest = { pendingToggle = null },
            title = { Text(label) },
            text = { Text("将「$label」设置为「${if (value) "开启" else "关闭"}」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            ApiClient.setMihomoConfig(instanceId, key, value)
                            configs = ApiClient.getMihomoConfigs(instanceId)
                        } catch (t: Throwable) {
                            error = "设置失败：${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            pendingToggle = null
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pendingToggle = null }) { Text("取消") } }
        )
    }

    pendingSelect?.let { (group, target) ->
        AlertDialog(
            onDismissRequest = { if (!selecting) pendingSelect = null },
            title = { Text("切换节点") },
            text = { Text("将「$group」切换到「$target」？") },
            confirmButton = {
                TextButton(enabled = !selecting, onClick = {
                    scope.launch {
                        selecting = true
                        try {
                            val result = ApiClient.selectMihomoGroup(instanceId, group, target)
                            if (!result.applied) error = "切换未生效，当前为 ${result.now ?: "未知"}"
                            else error = null
                            overview = ApiClient.getMihomoOverview()
                        } catch (t: Throwable) {
                            error = "切换失败：${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            selecting = false
                            pendingSelect = null
                        }
                    }
                }) { Text("确认切换") }
            },
            dismissButton = { TextButton(enabled = !selecting, onClick = { pendingSelect = null }) { Text("取消") } }
        )
    }

    if (panelError != null) {
        AlertDialog(
            onDismissRequest = { panelError = null },
            title = { Text("管理面板") },
            text = { Text(panelError ?: "") },
            confirmButton = { TextButton(onClick = { panelError = null }) { Text("关闭") } }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("代理", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item(key = "instances") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                overview?.instances?.forEach { item ->
                    val activeInstance = item.id == instanceId
                    Box(
                        Modifier.weight(1f).heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (activeInstance) BlueSoft else Color.Transparent)
                            .clickable { instanceId = item.id }
                            .semantics { contentDescription = "代理实例 ${item.name}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(item.name, color = if (activeInstance) Blue else Muted, fontSize = 13.sp, fontWeight = if (activeInstance) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        item(key = "tabs") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("概览", "策略组", "连接", "规则", "设置").forEach { name ->
                    val active = tab == name
                    Box(
                        Modifier.heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) Blue else Color.Transparent)
                            .clickable { tab = name }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(name, color = if (active) Color.White else Muted, fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        if (instance == null && loading) item(key = "loading") { ProxyEmpty("正在读取代理状态…") }
        val inst = instance
        if (inst != null) {
            if (inst.available == false) {
                item(key = "unavailable") { ProxyEmpty(inst.error ?: "该实例暂时不可达") }
            } else when (tab) {
                "概览" -> {
                    item(key = "overview") {
                        FrostCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Mihomo ${inst.version}", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text("${inst.mode} · 混合端口 ${inst.mixedPort ?: "-"}", color = Muted, fontSize = 11.sp)
                                }
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        try {
                                            val info = ApiClient.getMihomoPanelUrl(inst.id)
                                            val absolute = if (info.url.startsWith("http")) info.url
                                                else ApiClient.baseUrl.trimEnd('/') + info.url
                                            nav.navigate("proxy_panel?url=${android.net.Uri.encode(absolute)}")
                                        } catch (t: Throwable) {
                                            panelError = "面板暂时不可用：${t.message ?: t.javaClass.simpleName}"
                                        }
                                    }
                                }) { Text("管理面板", fontSize = 12.sp) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProxyMetric("连接", inst.connections.toString(), Blue, Modifier.weight(1f))
                                ProxyMetric("下行", formatRateShort(inst.downloadSpeed.toDouble()), Green, Modifier.weight(1f))
                                ProxyMetric("上行", formatRateShort(inst.uploadSpeed.toDouble()), Amber, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("累计 ↓ ${formatBytes(inst.downloadTotal)} · ↑ ${formatBytes(inst.uploadTotal)}",
                                color = Subtle, fontSize = 10.sp)
                        }
                    }
                    val airportInstance = airports?.instances?.firstOrNull { it.id == inst.id }
                    if (airportInstance != null && airportInstance.items.isNotEmpty()) {
                        item(key = "airport") {
                            FrostCard {
                                Text("机场看板", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                AirportBoard(airportInstance, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                "策略组" -> {
                    if (inst.groups.isEmpty()) {
                        item(key = "no-groups") { ProxyEmpty("该实例没有可切换的策略组") }
                    }
                    items(inst.groups, key = { "group:${it.name}" }) { group ->
                        val expanded = expandedGroup == group.name
                        FrostCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(group.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${group.type} · 当前 ${group.now ?: "-"}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (group.type == "Selector") {
                                    TextButton(onClick = {
                                        expandedGroup = if (expanded) null else group.name
                                    }, enabled = !selecting) {
                                        Text(if (expanded) "收起" else "切换", color = Blue, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (group.type == "Selector" && expanded) {
                                Spacer(Modifier.height(6.dp))
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    group.all.forEach { node ->
                                        val selected = node == group.now
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(enabled = !selecting && !selected) { pendingSelect = group.name to node }
                                                .padding(horizontal = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(node, color = if (selected) Blue else Ink, fontSize = 12.sp,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            if (selected) Text("当前", color = Blue, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            if (group.type == "Selector" && group.now != null && !expanded) {
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    val delay = delayByNode[group.now]
                                    Text(if (delay != null && delay > 0) "当前节点 ${delay}ms" else "当前节点待测速", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(enabled = testingNode == null, onClick = {
                                        scope.launch {
                                            testingNode = group.now
                                            try {
                                                val result = ApiClient.testMihomoNode(inst.id, group.now)
                                                delayByNode = delayByNode + (group.now to result.delay)
                                            } catch (t: Throwable) {
                                                error = "测速失败：${t.message ?: t.javaClass.simpleName}"
                                            } finally {
                                                testingNode = null
                                            }
                                        }
                                    }) { Text(if (testingNode == group.now) "测速中" else "测速", color = Blue, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
                "连接" -> {
                    item(key = "connections-header") {
                        FrostCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("活动连接", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text("${connections?.connections?.size ?: 0} 条 · 仅显示前 100 条", color = Muted, fontSize = 11.sp)
                                }
                                TextButton(enabled = !closingAll && !connections?.connections.isNullOrEmpty(),
                                    onClick = { confirmCloseAll = true }) {
                                    Text("全部关闭", color = Red, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    val rows = connections?.connections.orEmpty()
                    if (rows.isEmpty()) {
                        item(key = "no-connections") { ProxyEmpty("暂无活动连接") }
                    } else {
                        items(rows, key = { row -> "conn:${row.id.ifEmpty { row.hashCode().toString() }}" }) { conn ->
                            FrostCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(conn.host.ifEmpty { conn.destination.ifEmpty { "未知目标" } },
                                            color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${conn.network.uppercase()} :${conn.port} · ${conn.rule}",
                                            color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(conn.chains.joinToString(" → ").ifEmpty { "直连" },
                                            color = Subtle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("↓ ${formatBytes(conn.download)}", color = Green, fontSize = 10.sp)
                                        Text("↑ ${formatBytes(conn.upload)}", color = Amber, fontSize = 10.sp)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                ApiClient.closeMihomoConnection(inst.id, conn.id)
                                                connections = ApiClient.getMihomoConnections(inst.id)
                                            } catch (t: Throwable) {
                                                error = "关闭失败：${t.message ?: t.javaClass.simpleName}"
                                            }
                                        }
                                    }) { Text("关闭", color = Red, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
                "规则" -> {
                    val rows = rules?.rules.orEmpty()
                    item(key = "rules-header") {
                        FrostCard {
                            Text("规则", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("${rows.size} 条 · 禁用后立即生效", color = Muted, fontSize = 11.sp)
                        }
                    }
                    if (rows.isEmpty()) {
                        item(key = "no-rules") { ProxyEmpty("暂无规则") }
                    } else {
                        items(rows, key = { "rule:${it.index}" }) { rule ->
                            FrostCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${rule.type} ${rule.payload}", color = if (rule.disabled) Subtle else Ink,
                                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("→ ${rule.proxy.ifEmpty { "-" }}", color = Muted, fontSize = 10.sp)
                                    }
                                    TextButton(onClick = { pendingRule = rule }) {
                                        Text(if (rule.disabled) "启用" else "禁用",
                                            color = if (rule.disabled) Green else Red, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    item(key = "config") {
                        FrostCard {
                            Text("运行设置", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            ConfigRow("代理模式", configs?.mode ?: "-") { configChoice = "mode" }
                            ConfigRow("日志级别", configs?.logLevel ?: "-") { configChoice = "log-level" }
                            ConfigRow("允许局域网", if (configs?.allowLan == true) "是" else "否") {
                                pendingToggle = "allow-lan" to (configs?.allowLan != true)
                            }
                            ConfigRow("IPv6", if (configs?.ipv6 == true) "是" else "否") {
                                pendingToggle = "ipv6" to (configs?.ipv6 != true)
                            }
                            Text("混合端口 ${configs?.mixedPort ?: "-"} · SOCKS ${configs?.socksPort ?: "-"}",
                                color = Subtle, fontSize = 10.sp)
                        }
                    }
                    item(key = "providers-header") {
                        FrostCard {
                            Text("订阅与节点组", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("更新会重新拉取机场订阅，健康检查会测试该组节点。",
                                color = Muted, fontSize = 10.sp)
                        }
                    }
                    val rows = providers?.providers.orEmpty()
                    if (rows.isEmpty()) {
                        item(key = "no-providers") { ProxyEmpty("暂无 provider 数据") }
                    } else {
                        items(rows, key = { "provider:${it.name}" }) { provider ->
                            FrostCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(provider.name, color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${provider.vehicleType} · ${provider.nodeCount} 节点",
                                            color = Muted, fontSize = 10.sp)
                                    }
                                    TextButton(enabled = providerBusy == null, onClick = {
                                        scope.launch {
                                            providerBusy = "${provider.name}:update"
                                            try {
                                                ApiClient.runMihomoProviderAction(inst.id, provider.name, "update")
                                                providers = ApiClient.getMihomoProviders(inst.id)
                                                overview = ApiClient.getMihomoOverview()
                                            } catch (t: Throwable) {
                                                error = "更新失败：${t.message ?: t.javaClass.simpleName}"
                                            } finally {
                                                providerBusy = null
                                            }
                                        }
                                    }) { Text("更新", color = Blue, fontSize = 11.sp) }
                                    TextButton(enabled = providerBusy == null, onClick = {
                                        scope.launch {
                                            providerBusy = "${provider.name}:healthcheck"
                                            try {
                                                ApiClient.runMihomoProviderAction(inst.id, provider.name, "healthcheck")
                                                error = null
                                            } catch (t: Throwable) {
                                                error = "健康检查失败：${t.message ?: t.javaClass.simpleName}"
                                            } finally {
                                                providerBusy = null
                                            }
                                        }
                                    }) { Text("测速", color = Green, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onClick) { Text("修改", color = Blue, fontSize = 11.sp) }
    }
}

@Composable
private fun ProxyMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Subtle, fontSize = 10.sp)
    }
}


@Composable
private fun ProxyEmpty(message: String) {
    Surface(color = BlueSoft, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}
