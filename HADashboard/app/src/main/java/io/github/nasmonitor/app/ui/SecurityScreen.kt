package io.github.nasmonitor.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nasmonitor.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SecData(
    val host: String? = null,
    val source: String? = null,
    val status: String? = null,
    val message: String? = null,
    @com.google.gson.annotations.SerializedName("timestamp") val timestamp: String? = null,
    @com.google.gson.annotations.SerializedName("updated_at") val updatedAt: String? = null,
    @com.google.gson.annotations.SerializedName("stale") val stale: Boolean? = null,
    @com.google.gson.annotations.SerializedName("is_stale") val isStale: Boolean? = null,
    val safeline: SafelineStats? = null,
    @com.google.gson.annotations.SerializedName("hdd_temps") val hddTemps: Map<String, Int>? = null,
    @com.google.gson.annotations.SerializedName("ha_status") val haStatus: String? = null,
    val firewalld: Map<String, Any> = emptyMap(),
    val fail2ban: Map<String, Any> = emptyMap(),
    val ssh: Map<String, Any> = emptyMap(),
    val aegis: Map<String, Any> = emptyMap(),
    val coraza: Map<String, Any> = emptyMap()
)

data class SafelineStats(
    @com.google.gson.annotations.SerializedName("today_requests")
    val todayRequests: String? = null,
    @com.google.gson.annotations.SerializedName("today_ips") val todayIps: String? = null,
    @com.google.gson.annotations.SerializedName("today_internal") val todayInternal: String? = null,
    @com.google.gson.annotations.SerializedName("today_anomaly") val todayAnomaly: String? = null,
    val status: String? = null,
    @com.google.gson.annotations.SerializedName("week_ips") val weekIps: String? = null,
    @com.google.gson.annotations.SerializedName("week_requests") val weekRequests: String? = null,
    @com.google.gson.annotations.SerializedName("top_ip_count") val topIpCount: String? = null,
    @com.google.gson.annotations.SerializedName("top_ips") val topIps: List<TopIp> = emptyList()
)

data class TopIp(
    val rank: Int = 0,
    val ip: String = "",
    val count: Int = 0,
    val errors: Int = 0,
    val geo: String = ""
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SecurityScreen() {
    val managedServers by ServerRegistry.servers.collectAsState()
    LaunchedEffect(Unit) { runCatching { ApiClient.getServers() } }
    val hosts = managedServers.map { SecurityHost(it.id, it.name, "服务器安全状态", if (it.id == "nas") Icons.Default.Storage else Icons.Default.Cloud) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { hosts.size }
    val selected = pagerState.currentPage.coerceIn(0, hosts.lastIndex)

    Scaffold(containerColor = PageBackground, topBar = {
        SecurityHostTabs(hosts, selected) { target -> scope.launch { pagerState.animateScrollToPage(target) } }
    }) { pad ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(pad),
            key = { hosts[it].id }
        ) { page -> SecurityHostPage(hosts[page]) }
    }
}

private data class SecurityHost(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
private fun SecurityHostTabs(hosts: List<SecurityHost>, selected: Int, onSelect: (Int) -> Unit) {
    ContentSizedTabs(hosts.map { it.label }, selected, onSelect)
}

@Composable
private fun SecurityHostPage(host: SecurityHost) {
    if (host.id !in listOf("nas", "aliyun", "tencent")) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("尚未接入安全采集", color = Ink)
            Spacer(Modifier.height(8.dp))
            Text("Glances 提供系统与容器监控。此服务器暂未配置独立安全采集器。", color = Muted)
        }
        return
    }
    val cacheKey = "security_${host.id}"
    val cached = remember(host.id) { DataCache.get(cacheKey, SecData::class.java) }
    var secData by remember(host.id) { mutableStateOf<SecData?>(cached) }
    var err by remember(host.id) { mutableStateOf<String?>(null) }
    var liveData by remember(host.id) { mutableStateOf<LiveSecurityData?>(null) }
    var liveError by remember(host.id) { mutableStateOf<String?>(null) }
    var liveLoading by remember(host.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var loading by remember(host.id) { mutableStateOf(false) }
    fun loadLive() {
        if (host.id != "nas" || liveLoading) return
        liveLoading = true
        scope.launch {
            try {
                liveData = ApiClient.getLiveSecurityData(host.id)
                liveError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                liveError = e.message ?: "访问面板加载失败"
            } finally {
                liveLoading = false
            }
        }
    }
    fun load() {
        if (loading) return
        loading = true
        loadLive()
        scope.launch {
            try {
                val fresh = ApiClient.getSecurityData(host.id)
                secData = fresh
                DataCache.set(cacheKey, fresh)
                err = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
            } finally { loading = false }
        }
    }
    LaunchedEffect(host.id) { load() }

    RefreshContainer(loading || liveLoading, { load() }, Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()).padding(bottom = LocalFloatingNavigationPadding.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading && secData == null) LoadFeedback("正在加载安全状态…")
        err?.let { LoadFeedback("加载失败：$it · 下拉重试") }
        if (secData == null) {
            if (host.id == "nas") {
                if (liveLoading && liveData == null) LoadFeedback("正在加载访问面板…")
                liveError?.let { LoadFeedback("访问面板刷新失败：$it · 下拉重试") }
                liveData?.let { live ->
                    LiquidAwareCard(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(2.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("访问面板（主数据不可用）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            live.hosts.forEach { item -> MaskedText(item, semanticLabel = "正在访问的域名") }
                            Text("最近访问 IP：${live.rows.size} 条", fontSize = 11.sp, color = Muted)
                        }
                    }
                }
            }
        } else if (host.id == "aliyun" || host.id == "tencent") {
            if (secData?.status == "unavailable") {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(AmberSoft)) {
                    Text(secData?.message ?: "${host.label}安全数据源未接入", color = Amber, modifier = Modifier.padding(14.dp))
                }
            }
            SecurityMapCard(if (host.id == "tencent") "主机防火墙" else "防火墙", secData?.firewalld.orEmpty())
            SecurityMapCard("防暴力破解", secData?.fail2ban.orEmpty())
            SecurityMapCard("SSH 登录", secData?.ssh.orEmpty())
            if (host.id == "tencent") SecurityMapCard("系统审计与内存保护", secData?.aegis.orEmpty())
            else {
                SecurityMapCard("云安全中心", secData?.aegis.orEmpty())
                SecurityMapCard("Web 应用防护", secData?.coraza.orEmpty())
            }
            if (secData?.status == "unavailable" && secData?.firewalld.orEmpty().isEmpty()) {
                Text("当前仅保留已知主机安全能力，等待采集器接入。", fontSize = 11.sp, color = Muted)
            }
        } else {
            val s = secData?.safeline
            s?.let { data ->
                LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("雷池 WAF", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Spacer(Modifier.weight(1f))
                            val online = data.status == "online"
                            StatusBadge(if (online) "运行中" else "离线", if (online) "ok" else "err")
                        }
                        Spacer(Modifier.height(12.dp)); Divider(color = Track); Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatBoxLocal(data.todayRequests ?: "0", "今日请求", Blue)
                            StatBoxLocal(data.todayAnomaly ?: "0", "异常请求", Red)
                            StatBoxLocal(data.todayIps ?: "0", "公网IP", Amber)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatBoxLocal(data.todayInternal ?: "0", "内网请求", Muted)
                            StatBoxLocal(data.weekRequests ?: "0", "7天请求", Blue)
                            StatBoxLocal(data.weekIps ?: "0", "7天IP", Amber)
                        }
                    }
                }
            }
            if (s?.topIps?.isNotEmpty() == true) {
                LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("今日访问地址前 5 名", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        s.topIps.forEachIndexed { index, item ->
                            if (index > 0) Divider(color = Track)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((index + 1).toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue, modifier = Modifier.width(22.dp))
                                Column(Modifier.weight(1f)) {
                                    MaskedText(
                                        item.ip,
                                        modifier = Modifier.fillMaxWidth(),
                                        semanticLabel = "今日访问来源 IP"
                                    )
                                    Text(item.geo.ifEmpty { "归属地未知" }, fontSize = 10.sp, color = Muted, maxLines = 1)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${item.count} 次", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                    if (item.errors > 0) Text("异常 ${item.errors}", fontSize = 10.sp, color = Red)
                                }
                            }
                        }
                    }
                }
            }
            if (liveLoading && liveData == null) LoadFeedback("正在加载访问面板…")
            liveError?.let { LoadFeedback("访问面板刷新失败：$it · 下拉重试") }
            liveData?.let { live ->
                LiquidAwareCard(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(2.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正在访问的域名", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Spacer(Modifier.weight(1f)); Text("${live.total} 次 · ${live.unique} IP", fontSize = 10.sp, color = Subtle)
                        }
                        Spacer(Modifier.height(8.dp))
                        live.hosts.forEach { item -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (liveError == null) Green else Amber, RoundedCornerShape(3.dp))); Spacer(Modifier.width(7.dp)); MaskedText(item, modifier = Modifier.weight(1f), semanticLabel = "正在访问的域名"); Text(if (liveError == null) "访问中" else "上次记录", fontSize = 10.sp, color = if (liveError == null) Green else Amber)
                        } }
                        Spacer(Modifier.height(10.dp)); Divider(color = Track); Spacer(Modifier.height(8.dp)); Text("最近访问 IP", fontSize = 12.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                        live.rows.take(5).forEach { entry -> Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            MaskedText(
                                entry.ip,
                                modifier = Modifier.fillMaxWidth(),
                                semanticLabel = "最近访问 IP"
                            ); Row(Modifier.fillMaxWidth()) { Text(entry.geo.ifEmpty { "归属地未知" }, fontSize = 9.sp, color = Subtle, modifier = Modifier.weight(1f)); Text("${entry.count}次", fontSize = 10.sp, color = if (entry.errors > 0) Red else Ink) }
                        } }
                    }
                }
            }
            secData?.hddTemps?.let { hdd ->
                if (hdd.isNotEmpty()) LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) { Text("硬盘温度", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink); Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { hdd.entries.sortedBy { it.key }.forEach { (name, temp) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$temp°C", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (temp > 50) Red else if (temp > 45) Amber else Green); Text(name.uppercase(), fontSize = 10.sp, color = Subtle) } } } }
                }
            }
        }
    }
    }
}

private fun securityKeyCn(key: String): String = when (key) {
    "service" -> "服务"
    "service_active" -> "服务状态"
    "service_enabled" -> "开机启动"
    "active" -> "运行状态"
    "enabled" -> "开机启用"
    "default_zone" -> "默认区域"
    "overlay_trusted" -> "组网访问"
    "jail" -> "防护规则"
    "currently_failed" -> "当前失败次数"
    "currently_banned" -> "当前封禁数量"
    "total_banned" -> "累计封禁数量"
    "maxauthtries" -> "最大认证尝试次数"
    "logingracetime" -> "登录宽限时间"
    "x11forwarding" -> "X11 远程转发"
    "permitemptypasswords" -> "允许空密码"
    "clientaliveinterval" -> "客户端保活间隔"
    "clientalivecountmax" -> "客户端保活次数"
    "aliyundun_active" -> "云安全中心"
    "aliyundun_monitor_active" -> "云安全监控"
    "container" -> "容器"
    "running" -> "运行状态"
    "collected_at" -> "采集时间"
    else -> key.replace('_', ' ')
}

private fun securityValueCn(key: String, value: Any): String {
    if (value is Boolean) return if (value) "是" else "否"
    val text = value.toString()
    return when (key) {
        "service" -> when (text.lowercase()) { "firewalld" -> "防火墙服务"; "ufw" -> "主机防火墙"; else -> text }
        "jail" -> if (text.lowercase() == "sshd") "SSH 防护" else text
        "default_zone" -> when (text.lowercase()) { "public" -> "公共区域"; "trusted" -> "信任区域"; "docker" -> "容器网络区域"; else -> text }
        "x11forwarding" -> if (text.lowercase() == "no") "已关闭" else if (text.lowercase() == "yes") "已开启" else text
        "permitemptypasswords" -> if (text.lowercase() == "no") "已禁止" else if (text.lowercase() == "yes") "已允许" else text
        "clientaliveinterval", "logingracetime" -> if (text.matches(Regex("\\d+"))) "$text 秒" else text
        "clientalivecountmax", "maxauthtries", "currently_failed", "currently_banned", "total_banned" -> if (text.matches(Regex("\\d+"))) "$text 次" else text
        "active", "service_active", "running" -> when (text.lowercase()) { "active", "running", "true" -> "运行中"; "inactive", "stopped", "false" -> "未运行"; else -> text }
        "enabled", "service_enabled" -> when (text.lowercase()) { "enabled", "true" -> "已启用"; "disabled", "false" -> "未启用"; else -> text }
        else -> when (text.lowercase()) {
            "active", "running" -> "运行中"
            "inactive", "stopped" -> "未运行"
            "enabled" -> "已启用"
            "disabled" -> "未启用"
            "true" -> "是"
            "false" -> "否"
            "unknown" -> "未知"
            else -> text
        }
    }
}

@Composable
private fun SecurityMapCard(title: String, data: Map<String, Any>) {
    if (data.isEmpty()) return
    LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            data.entries.sortedBy { it.key }.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(securityKeyCn(key), fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
                    Text(securityValueCn(key, value), fontSize = 11.sp, color = Ink, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun StatBoxLocal(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = Subtle)
    }
}
