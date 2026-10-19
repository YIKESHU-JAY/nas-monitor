@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.nasmonitor.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nasmonitor.app.*
import kotlinx.coroutines.launch
import kotlin.math.ceil

data class TrendGap(val startFraction: Float, val endFraction: Float)

internal fun trendGaps(times: List<Double>, thresholdSeconds: Double): List<TrendGap> {
    if (times.size < 2) return emptyList()
    val span = times.last() - times.first()
    if (span <= 0) return emptyList()
    return times.zipWithNext().mapNotNull { (start, end) ->
        val delta = end - start
        if (delta > thresholdSeconds) {
            TrendGap(
                startFraction = ((start - times.first()) / span).toFloat().coerceIn(0f, 1f),
                endFraction = ((end - times.first()) / span).toFloat().coerceIn(0f, 1f)
            )
        } else null
    }
}

internal fun chartNiceMaxY(rawMaxY: Float): Float {
    val value = rawMaxY.coerceAtLeast(1f)
    return when {
        value <= 5f -> ceil(value)
        value <= 10f -> ceil(value / 2f) * 2f
        value <= 50f -> ceil(value / 10f) * 10f
        value <= 100f -> ceil(value / 20f) * 20f
        else -> ceil(value / 20f) * 20f
    }
}

internal fun chartTicks(maxY: Float): List<Float> {
    val normalized = chartNiceMaxY(maxY)
    return (0..5).map { normalized * it / 5f }
}

// ── 数据类 ──
// data class TrendPoint(val t: Double, val cpu: Float, val mem: Float, val disk: Float, val rx: Float, val tx: Float)
// data class TrendResponse(val host: String, val minutes: Int, val points: List<TrendPoint>)
data class ContainerLogResponse(val name: String, val lines: Int, val logs: String)

// ── 趋势图组件 ──
@Composable
fun TrendLine(
    values: List<Float>,
    color: Color,
    label: String,
    unit: String,
    maxY: Float = 100f,
    times: List<Double> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return
    val usableTimes = times.takeIf { it.size == values.size && it.zipWithNext().all { (a, b) -> b >= a } }
    val intervals = usableTimes?.zipWithNext()?.map { (a, b) -> b - a }?.filter { it > 0 }.orEmpty()
    val expectedInterval = intervals.sorted().getOrNull(intervals.size / 2) ?: 0.0
    val gaps = if (usableTimes != null && expectedInterval > 0) trendGaps(usableTimes, expectedInterval * 2.5) else emptyList()
    // 曲线平滑动画
    val animatedVals = values.mapIndexed { i, v ->
        animateFloatAsState(
            targetValue = v,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            label = "trend$i"
        ).value
    }
    val cur = animatedVals.last()
    val chartMaxY = chartNiceMaxY(maxY)
    val ySteps = chartTicks(chartMaxY)
    LiquidAwareCard(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.weight(1f))
                Text("${"%.1f".format(cur)} $unit", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                // Y轴标签（左侧用 Text 列）
                Column(Modifier.fillMaxSize().padding(start = 2.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.Start) {
                    ySteps.reversed().forEach { yv -> Text(if (yv % 1f == 0f) yv.toInt().toString() else "%.1f".format(yv), fontSize = 9.sp, color = Subtle) }
                }
                // 图表
                Canvas(Modifier.fillMaxSize().padding(start = 30.dp)) {
                    val w = size.width
                    val h = size.height
                    val yRange = chartMaxY
                    // 网格线
                    ySteps.forEach { yv ->
                        val y = h - (yv / yRange * h).coerceIn(0f, h)
                        drawLine(ChartGrid, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
                    }
                    val gapBands = gaps
                    gapBands.forEach { gap ->
                        val startX = gap.startFraction * w
                        val endX = gap.endFraction * w
                        drawRect(color = Amber.copy(alpha = 0.07f), topLeft = Offset(startX, 0f), size = androidx.compose.ui.geometry.Size((endX - startX).coerceAtLeast(2f), h))
                    }
                    // 数据曲线
                    if (animatedVals.size >= 2) {
                        val path = Path()
                        var lastX: Float? = null
                        animatedVals.forEachIndexed { i, v ->
                            val x = if (usableTimes != null) {
                                val span = usableTimes.last() - usableTimes.first()
                                if (span > 0) ((usableTimes[i] - usableTimes.first()) / span * w).toFloat() else i.toFloat() / (animatedVals.size - 1) * w
                            } else i.toFloat() / (animatedVals.size - 1) * w
                            val y = h - (v / yRange * h).coerceIn(0f, h)
                            val broken = usableTimes != null && i > 0 && gaps.any { gap ->
                                val gapStart = gap.startFraction * w
                                val gapEnd = gap.endFraction * w
                                val previousX = lastX ?: x
                                previousX <= gapStart && x >= gapEnd || previousX < gapStart && x > gapStart
                            }
                            if (i == 0 || broken) path.moveTo(x, y) else path.lineTo(x, y)
                            lastX = x
                        }
                        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    gapBands.forEach { gap ->
                        if (gap.endFraction > gap.startFraction) {
                            drawLine(Amber.copy(alpha = 0.35f), Offset(gap.startFraction * w, 0f), Offset(gap.startFraction * w, h), strokeWidth = 1f)
                            drawLine(Amber.copy(alpha = 0.35f), Offset(gap.endFraction * w, 0f), Offset(gap.endFraction * w, h), strokeWidth = 1f)
                        }
                    }
                }
            }
            if (gaps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("琥珀色区间为数据空档，曲线在空档处断开。", fontSize = 10.sp, color = Amber)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(host: String, onBack: () -> Unit) {
    var data by remember { mutableStateOf<TrendResponse?>(null) }
    var minutes by remember { mutableStateOf(60) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    // Keyed effect cancels the previous range request before accepting a new result.
    LaunchedEffect(host, minutes, retry) {
        loading = true
        error = null
        try {
            data = ApiClient.getTrends(host, minutes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    fun load() { retry++ }

    Scaffold(containerColor = PageBackground, topBar = {
        Column {
            AppPageHeader("${hostLabel(host)} 趋势", onBack = onBack)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 360, 1440).forEach { option ->
                    FilterChip(selected = minutes == option, onClick = { minutes = option },
                        label = { Text("${option / 60} 小时") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BlueSoft, selectedLabelColor = Blue))
                }
            }
        }
    }) { pad ->
        RefreshContainer(loading, { load() }, Modifier.padding(pad)) {
            when {
                error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    LoadFeedback("加载失败：$error · 下拉重试")
                }
                data == null || data!!.points.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    LoadFeedback(if (data == null) "正在加载趋势…" else "暂无趋势数据，等待采集")
                }
                else -> {
                    val pts = data!!.points
                    val times = pts.map { it.t }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        item { Text("CPU 使用率", fontSize = 13.sp, color = Muted) }
                        item { TrendLine(pts.map { it.cpu }, Blue, "CPU", "%", maxY = 100f, times = times) }
                        item { Text("内存使用率", fontSize = 13.sp, color = Muted) }
                        item { TrendLine(pts.map { it.mem }, Green, "内存", "%", maxY = 100f, times = times) }
                        item { Text("磁盘占用", fontSize = 13.sp, color = Muted) }
                        item { TrendLine(pts.map { it.disk }, Amber, "磁盘", "%", maxY = 100f, times = times) }
                        item { Text("网络流量", fontSize = 13.sp, color = Muted) }
                        val maxTraffic = (pts.maxOfOrNull { (it.rx / 1024).coerceAtLeast(it.tx / 1024) } ?: 1f) * 1.3f; item { TrendLine(pts.map { it.rx / 1024 }, Amber, "下载", "KB/s", maxY = maxTraffic, times = times) }
                        item { TrendLine(pts.map { it.tx / 1024 }, Blue, "上传", "KB/s", maxY = maxTraffic, times = times) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemperatureScreen(host: String, onBack: () -> Unit) {
    var data by remember { mutableStateOf<TemperatureTrendResponse?>(null) }
    var minutes by remember { mutableStateOf(60) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    // Keyed effect cancels the previous range request before accepting a new result.
    LaunchedEffect(host, minutes, retry) {
        loading = true
        error = null
        try {
            data = ApiClient.getTemperatureTrends(host, minutes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    fun load() { retry++ }

    Scaffold(containerColor = PageBackground, topBar = {
        Column {
            AppPageHeader("${hostLabel(host)} 温度趋势", onBack = onBack)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 360, 1440).forEach { option ->
                    FilterChip(selected = minutes == option, onClick = { minutes = option },
                        label = { Text("${option / 60} 小时") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BlueSoft, selectedLabelColor = Blue))
                }
            }
        }
    }) { padding ->
        RefreshContainer(loading, { load() }, Modifier.padding(padding)) {
            when {
                error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    LoadFeedback("加载失败：$error · 下拉重试")
                }
                data == null || data!!.disks.values.all { it.isEmpty() } -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    LoadFeedback(if (data == null) "正在加载温度趋势…" else "暂无温度趋势数据，等待采集")
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    data!!.disks.filterValues { it.isNotEmpty() }.toSortedMap().forEach { (name, points) ->
                        item {
                            val latest = points.last().temp
                            val color = when {
                                latest > 55f -> Red
                                latest >= 45f -> Amber
                                else -> Green
                            }
                            val maxY = maxOf(60f, (points.maxOfOrNull { it.temp } ?: 60f) + 5f)
                            TrendLine(points.map { it.temp }, color, name, "°C", maxY)
                        }
                    }
                }
            }
        }
    }
}

fun hostLabel(host: String): String = when (host) {
    "nas" -> "NAS"
    "aliyun" -> "阿里云"
    "tencent" -> "腾讯云"
    else -> ServerRegistry.name(host)
}

// ── 容器列表页（参考 Docker 管理页风格） ──
@Composable
fun ContainersScreen(host: String, onBack: () -> Unit) {
    var containers by remember { mutableStateOf<List<ContainerInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var logName by remember { mutableStateOf<String?>(null) }
    var logContent by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") } // all/running/stopped
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var logLoading by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    var logJob by remember { mutableStateOf<Job?>(null) }
    var logRequest by remember { mutableIntStateOf(0) }
    var pendingAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try { containers = ApiClient.getContainers(host).containers; loaded = true; error = null }
            catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "加载失败" }
            finally { loading = false }
        }
    }
    LaunchedEffect(host) { load() }
    fun closeLog() { logRequest++; logJob?.cancel(); logName = null; logContent = null; logError = null; logLoading = false }
    fun loadLog(name: String) {
        val request = ++logRequest
        logJob?.cancel()
        logName = name
        logContent = null
        logError = null
        logLoading = true
        logJob = scope.launch {
            try { val content = ApiClient.getContainerLog(host, name); if (request == logRequest) logContent = content }
            catch (e: Exception) { if (e is CancellationException) throw e; if (request == logRequest) logError = e.message ?: "加载失败" }
            finally { if (request == logRequest) logLoading = false }
        }
    }
    fun runAction(name: String, action: String) {
        if (actionBusy) return
        pendingAction = null
        actionBusy = true
        actionMessage = null
        scope.launch {
            try {
                val refreshed = ApiClient.containerAction(host, name, action)
                if (refreshed.error != null) {
                    actionMessage = "$name ${when (action) { "start" -> "已启动"; "stop" -> "已停止"; else -> "已重启" }}，但列表刷新失败：${refreshed.error}"
                } else {
                    containers = refreshed.containers
                    actionMessage = "$name ${when (action) { "start" -> "已启动"; "stop" -> "已停止"; else -> "已重启" }}"
                }
                loaded = true
                error = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                actionMessage = "操作失败：${e.message ?: "未知错误"}"
            } finally {
                actionBusy = false
            }
        }
    }
    BackHandler(enabled = logName != null) { closeLog() }

    val running = containers.count { it.status == "running" }
    val stopped = containers.size - running
    val shown = when (filter) {
        "running" -> containers.filter { it.status == "running" }
        "stopped" -> containers.filter { it.status != "running" }
        else -> containers
    }

    Scaffold(containerColor = PageBackground, topBar = {
        if (logName != null) AppPageHeader("容器日志", onBack = ::closeLog, subtitle = "${hostLabel(host)} · $logName")
        else AppPageHeader("${hostLabel(host)} 容器", onBack = onBack)
    }) { pad ->
        if (logName != null) {
            RefreshContainer(logLoading, { logName?.let { loadLog(it) } }, Modifier.padding(pad)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    if (logLoading) LoadFeedback("正在加载日志…")
                    logError?.let { LoadFeedback("日志加载失败：$it · 下拉重试") }
                    if (!logLoading && logError == null) {
                        Surface(shape = RoundedCornerShape(16.dp), color = LogPanelBg, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                            SelectionContainer {
                                Text(logContent?.ifBlank { "暂无日志" } ?: "暂无日志", fontSize = 12.sp,
                                    color = LogPanelFg, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        } else {
            RefreshContainer(loading, { load() }, Modifier.padding(pad)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (host != "nas") item { Text("当前主机仅提供容器清单，暂不支持读取日志。", color = Muted, fontSize = 12.sp) }
                    if (host == "nas") item {
                        Text("仅支持 NAS 本机容器的启动、停止与重启；危险操作需二次确认。", color = Muted, fontSize = 12.sp)
                    }
                    actionMessage?.let { message -> item { LoadFeedback(message) } }
                    if (loading && !loaded) item { LoadFeedback("正在加载容器…") }
                    if (error != null) item { LoadFeedback(if (loaded) "刷新失败，显示上次数据：$error · 下拉重试" else "加载失败：$error · 下拉重试") }
                    if (loaded) {
                        item {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SummaryChip("$running 运行中", Green, GreenSoft)
                                SummaryChip("$stopped 已停止", if (stopped > 0) Amber else Muted, if (stopped > 0) AmberSoft else Track)
                                SummaryChip("共 ${containers.size}", Blue, BlueSoft)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("all" to "全部", "running" to "运行中", "stopped" to "已停止").forEach { (k, label) ->
                                    FilterChip(
                                        selected = filter == k,
                                        onClick = { filter = k },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BlueSoft, selectedLabelColor = Blue)
                                    )
                                }
                            }
                        }
                        if (shown.isEmpty()) item { LoadFeedback(if (containers.isEmpty()) "暂无容器" else "没有符合筛选条件的容器") }
                        items(shown) { c ->
                            val runningNow = c.status == "running"
                            LiquidAwareCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (runningNow) GreenSoft else Track), contentAlignment = Alignment.Center) {
                                            Text("D", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (runningNow) Green else Muted)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
                                            Text(c.image, fontSize = 11.sp, color = Muted, maxLines = 1)
                                        }
                                        Surface(shape = RoundedCornerShape(20.dp), color = if (c.healthy) GreenSoft else if (runningNow) AmberSoft else RedSoft) {
                                            Text(
                                                if (c.healthy) "健康" else if (runningNow) "运行中" else "已停止",
                                                fontSize = 11.sp,
                                                color = if (c.healthy) Green else if (runningNow) Amber else Red,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    if (c.ports.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Surface(shape = RoundedCornerShape(8.dp), color = CardTint) {
                                            Text("端口 ${c.ports}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Divider(color = Track)
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.status, fontSize = 11.sp, color = Subtle)
                                        if (host == "nas") {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (!runningNow) {
                                                    TextButton(enabled = !actionBusy, onClick = { pendingAction = c.name to "start" }) { Text("启动", color = Green, fontSize = 12.sp) }
                                                } else {
                                                    TextButton(enabled = !actionBusy, onClick = { pendingAction = c.name to "restart" }) { Text("重启", color = Amber, fontSize = 12.sp) }
                                                    TextButton(enabled = !actionBusy, onClick = { pendingAction = c.name to "stop" }) { Text("停止", color = Red, fontSize = 12.sp) }
                                                }
                                                TextButton(onClick = { loadLog(c.name) }) { Text("日志", color = Blue, fontSize = 12.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingAction?.let { (name, action) ->
        val actionLabel = when (action) { "start" -> "启动"; "stop" -> "停止"; else -> "重启" }
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pendingAction = null },
            title = { Text("${actionLabel}容器") },
            text = { Text("确认${actionLabel}容器 $name？操作会中断该容器的当前连接。") },
            confirmButton = {
                TextButton(enabled = !actionBusy, onClick = { runAction(name, action) }) {
                    Text(if (actionBusy) "处理中…" else actionLabel, color = if (action == "stop") Red else Blue)
                }
            },
            dismissButton = { TextButton(enabled = !actionBusy, onClick = { pendingAction = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SummaryChip(text: String, color: Color, bg: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = bg) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
