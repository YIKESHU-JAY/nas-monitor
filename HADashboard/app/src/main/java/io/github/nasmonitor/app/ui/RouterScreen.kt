package io.github.nasmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 累计流量（非速率）：不带 /s。速率请用 formatRate。
@Composable
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${String.format("%.2f", bytes.toDouble() / 1024 / 1024 / 1024)} GB"
    bytes >= 1024L * 1024 -> "${String.format("%.2f", bytes.toDouble() / 1024 / 1024)} MB"
    bytes >= 1024L -> "${String.format("%.1f", bytes.toDouble() / 1024)} KB"
    else -> "$bytes B"
}

@Composable
fun formatRate(bytes: Long): String = if (bytes <= 0) "--" else "${formatBytes(bytes)}/s"

@Composable
fun RouterScreen(onBack: () -> Unit) {
    var data by remember { mutableStateOf<RouterStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var speedtesting by remember { mutableStateOf(false) }
    var selectedWan by remember { mutableStateOf("") }
    // Switching interfaces immediately drops the previous WAN's visible results.
    var speedtestResult by remember(selectedWan) { mutableStateOf<SpeedtestResult?>(null) }
    var speedtestHistory by remember(selectedWan) { mutableStateOf<List<SpeedtestRecord>>(emptyList()) }
    var historyLoading by remember(selectedWan) { mutableStateOf(true) }
    var historyError by remember(selectedWan) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reconcileWan(status: RouterStatus) {
        val valid = status.wans.filter { it.ip.isNotBlank() }.map { it.name }.filter { it.isNotBlank() }.distinct()
        val next = when {
            valid.isEmpty() -> ""
            selectedWan in valid -> selectedWan
            else -> valid.first()
        }
        if (next != selectedWan) selectedWan = next
    }
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val cached = DataCache.get<RouterStatus>("router")
                if (cached != null) {
                    data = cached
                    reconcileWan(cached)
                }
                val fresh = ApiClient.getRouterStatus()
                DataCache.set("router", fresh)
                data = fresh
                reconcileWan(fresh)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    // The effect is cancelled on WAN changes, so a late response cannot replace
    // the newly selected interface's history.
    LaunchedEffect(selectedWan) {
        val wan = selectedWan
        if (wan.isBlank()) {
            speedtestHistory = emptyList()
            speedtestResult = null
            historyError = null
            historyLoading = false
            return@LaunchedEffect
        }
        try {
            val records = ApiClient.getRouterSpeedtestHistory(wan, 30).items
                .filter { it.interfaceName == wan }
            speedtestHistory = records
            speedtestResult = records.firstOrNull { it.status == "ok" }?.let { rec ->
                SpeedtestResult(download = rec.downloadMbps, upload = rec.uploadMbps,
                    interfaceName = rec.interfaceName, timestamp = rec.timestamp)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            historyError = e.message ?: "请求失败"
        } finally {
            historyLoading = false
        }
    }

    fun startSpeedtest() {
        if (speedtesting || historyLoading || selectedWan.isBlank()) return
        val wan = selectedWan
        speedtesting = true; speedtestResult = null; historyError = null
        scope.launch {
            try {
                // Capture the previous result before starting; existing history
                // must not be mistaken for completion of this new speed test.
                val previousTimestamp = ApiClient.getRouterSpeedtestHistory(wan, 1).items
                    .filter { it.interfaceName == wan }.maxOfOrNull { it.timestamp } ?: ""
                val r1 = ApiClient.runRouterSpeedtest(wan)
                if (r1.error != null) {
                    speedtestResult = r1
                } else {
                    val deadline = System.currentTimeMillis() + 160000
                    while (isActive) {
                        delay(6000)
                        val hist = ApiClient.getRouterSpeedtestHistory(wan, 30).items
                            .filter { it.interfaceName == wan }
                        speedtestHistory = hist
                        val rec = hist.firstOrNull { it.status == "ok" && it.timestamp > previousTimestamp }
                        if (rec != null) {
                            speedtestResult = SpeedtestResult(download = rec.downloadMbps, upload = rec.uploadMbps,
                                interfaceName = rec.interfaceName, timestamp = rec.timestamp)
                            break
                        }
                        if (System.currentTimeMillis() > deadline) {
                            speedtestResult = SpeedtestResult(error = "测速超时或无结果")
                            break
                        }
                    }
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speedtestResult = SpeedtestResult(error = e.message ?: "请求失败")
            } finally {
                speedtesting = false
            }
        }
    }

    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("爱快路由器", onBack = onBack) {
        }
    }) { pad ->
        val scroll = rememberScrollState()
        RefreshContainer(loading, { load() }, Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data == null) {
                LoadFeedback(if (error == null) "正在加载路由状态…" else "加载失败：${error!!} · 下拉重试")
            } else {
                val r = data!!
                val cachedOrStale = error != null || loading
                error?.let { LoadFeedback("刷新失败：$it · 下拉重试") }

                // 状态卡片
                LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val currentlyKnown = !cachedOrStale
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (currentlyKnown && r.internet == "up") BlueSoft else if (currentlyKnown) RedSoft else AmberSoft), contentAlignment = Alignment.Center) {
                                Icon(if (!currentlyKnown) Icons.Default.HelpOutline else if (r.internet == "up") Icons.Default.CheckCircle else Icons.Default.Close, null, tint = if (!currentlyKnown) Amber else if (r.internet == "up") Blue else Red, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column { Text("爱快路由器", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink); Text("iKuai ${r.version}", fontSize = 11.sp, color = Muted, maxLines = 1) }
                            Spacer(Modifier.weight(1f))
                            StatusBadge(if (!currentlyKnown) "上次记录" else if (r.internet == "up") "在线" else "离线", if (!currentlyKnown) "warn" else if (r.internet == "up") "ok" else "err")
                        }
                        Spacer(Modifier.height(12.dp)); Divider(color = Track); Spacer(Modifier.height(12.dp))
                        // 系统指标
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatBox("${"%.0f".format(r.cpu_usage)}%", "CPU", if (r.cpu_usage > 70) Red else Blue)
                            StatBox("${"%.0f".format(r.mem_usage)}%", "内存", if (r.mem_usage > 70) Red else Green)
                            StatBox("${r.cpu_temp}°C", "温度", if (r.cpu_temp > 70) Red else Amber)
                            StatBox("${r.connections}", "连接数", Muted)
                        }
                        Spacer(Modifier.height(12.dp)); Divider(color = Track); Spacer(Modifier.height(12.dp))
                        // 各 WAN 口
                        r.wans.forEach { w ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (w.internet == "up") Green else Red))
                                Spacer(Modifier.width(8.dp))
                                Text(w.name.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.width(48.dp))
                                if (w.ip.isBlank()) {
                                    Text("未获取 IP", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
                                } else {
                                    MaskedText(
                                        w.ip,
                                        modifier = Modifier.weight(1f),
                                        semanticLabel = "${w.name.uppercase()} 公网 IP"
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("↓ " + formatRate(w.download_speed.toLong()), fontSize = 11.sp, color = Green)
                                    Spacer(Modifier.height(2.dp))
                                    Text("↑ " + formatRate(w.upload_speed.toLong()), fontSize = 11.sp, color = Amber)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(8.dp)); Divider(color = Track); Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            DetailLabel("在线设备", "${r.online_devices}", Modifier.weight(1f))
                            DetailLabel("DHCP 总数", "${r.total_devices}", Modifier.weight(1f))
                            DetailLabel("内存可用", "${"%.1f".format(r.mem_available)}/${"%.1f".format(r.mem_total)}GB", Modifier.weight(1f))
                        }
                    }
                }

                // 测速区域
                LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("宽带测速", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                Spacer(Modifier.weight(1f))
                                val wanOptions = r.wans.filter { it.ip.isNotEmpty() }.map { it.name }.filter { it.isNotBlank() }.distinct()
                                if (speedtesting) {
                                    Text("测速中...", fontSize = 12.sp, color = Amber)
                                    Spacer(Modifier.width(8.dp))
                                    
                                } else {
                                    PrimaryButton("开始测速", { startSpeedtest() }, enabled = wanOptions.isNotEmpty() && selectedWan.isNotBlank() && !speedtesting && !historyLoading,
                                        modifier = Modifier.heightIn(min = 40.dp))
                                }
                            }
                            // WAN 口选择
                            val wanOptions = r.wans.filter { it.ip.isNotEmpty() }.map { it.name }.filter { it.isNotBlank() }.distinct()
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                wanOptions.forEach { wan ->
                                    val selected = wan == selectedWan
                                    Surface(
                                        onClick = { if (!speedtesting) selectedWan = wan },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) BlueSoft else CardTint,
                                        enabled = !speedtesting
                                    ) {
                                        Text(wan.uppercase(), fontSize = 12.sp, color = if (selected) Blue else Muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                                    }
                                }
                                if (wanOptions.isEmpty()) Text("没有可用公网 WAN 接口，测速已禁用", fontSize = 11.sp, color = Muted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        speedtestResult?.let { st ->
                            if (st.error != null) { Text("失败：${st.error}", color = Red, fontSize = 12.sp) }
                            else {
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("${st.download} Mbps", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green)
                                            Text("下载", fontSize = 11.sp, color = Subtle)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("${st.upload} Mbps", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber)
                                            Text("上传", fontSize = 11.sp, color = Subtle)
                                        }
                                    }
                                    if (st.timestamp != null) {
                                        Text("测速时间：${st.timestamp?.replace("T", " ")?.substringBefore(".")?.substringBefore("+")}", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        } ?: run {
                            val message = when {
                                speedtesting -> "正在测试 ${selectedWan.uppercase()}，请稍候…"
                                historyLoading -> "正在加载 ${selectedWan.uppercase()} 测速记录…"
                                historyError != null -> "测速记录加载失败：$historyError"
                                else -> "${selectedWan.uppercase()} 暂无测速记录，点击「开始测速」测试宽带速度"
                            }
                            Text(message, fontSize = 12.sp, color = if (historyError != null) Red else Muted)
                        }

                        // 历史记录
                        if (speedtestHistory.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("历史记录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Spacer(Modifier.height(6.dp))
                            speedtestHistory.take(10).forEach { rec ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rec.timestamp.replace("T", " ").substringBefore(".").substringBefore("+"), fontSize = 11.sp, color = Muted)
                                        Text(rec.server.ifEmpty { rec.serverLocation }, fontSize = 10.sp, color = Subtle, maxLines = 1)
                                    }
                                    Text("${rec.downloadMbps} Mbps", fontSize = 12.sp, color = Green, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(10.dp))
                                    Text("${rec.uploadMbps} Mbps", fontSize = 12.sp, color = Amber, fontWeight = FontWeight.SemiBold)
                                }
                                Divider(color = Track, thickness = 1.dp)
                            }
                        }
                    }
                }

                // 在线设备列表
                if (r.devices.isNotEmpty()) {
                    Text("ARP 活跃设备（${r.devices.size}）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    val devs = r.devices.sortedBy { it.ip }
                    var i = 0
                    while (i < devs.size) {
                        val d1 = devs[i]
                        val d2 = if (i + 1 < devs.size) devs[i + 1] else null
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeviceCard(dev = d1, modifier = Modifier.weight(1f))
                            if (d2 != null) DeviceCard(dev = d2, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                        }
                        i += 2
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DetailLabel(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 10.sp, color = Subtle)
        Text(value, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DeviceCard(dev: RouterDevice, modifier: Modifier = Modifier) {
    LiquidAwareCard(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(CardWhite), elevation = CardDefaults.cardElevation(1.dp), modifier = modifier) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Green))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(dev.name.ifEmpty { "未知设备" }, fontSize = 12.sp, color = Ink, maxLines = 1, fontWeight = FontWeight.Medium)
                MaskedText(
                    dev.ip,
                    semanticLabel = "${dev.name.ifEmpty { "未知设备" }} 局域网 IP"
                )
            }
        }
    }
}
