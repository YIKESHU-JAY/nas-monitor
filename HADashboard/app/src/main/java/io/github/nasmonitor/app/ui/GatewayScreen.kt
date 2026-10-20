package io.github.nasmonitor.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import io.github.nasmonitor.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class GatewayNavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val gatewayNavItems = listOf(
    GatewayNavItem("概览", Icons.Default.Info),
    GatewayNavItem("性能", Icons.Default.Storage),
    GatewayNavItem("日志", Icons.Default.List),
    GatewayNavItem("渠道", Icons.Default.SettingsEthernet),
    GatewayNavItem("用量", Icons.Default.BarChart)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun GatewayScreen(nav: NavHostController) {
    var overview by remember { mutableStateOf<NewApiGatewayOverview?>(null) }
    var performance by remember { mutableStateOf<List<NewApiModelPerformance>?>(null) }
    var performanceLoading by remember { mutableStateOf(false) }
    var performanceError by remember { mutableStateOf<String?>(null) }
    var lastPerformanceLoadAt by remember { mutableStateOf(0L) }
    var affinity by remember { mutableStateOf<NewApiAffinityState?>(null) }
    var routingPolicy by remember { mutableStateOf<NewApiRoutingPolicy?>(null) }
    var clearAffinityPending by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<NewApiLogPage?>(null) }
    // 前后台感知：退到后台时暂停静默轮询，回到前台立刻恢复
    var lifecycleResumed by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lifecycleResumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { lifecycleResumed = false }
    val pagerState = rememberPagerState(initialPage = 0) { gatewayNavItems.size }
    val selectedTab = pagerState.currentPage
    var logPage by remember { mutableStateOf(0) }
    var logTokens by remember { mutableStateOf<List<String>>(emptyList()) }
    var logTokenFilter by remember { mutableStateOf<String?>(null) }
    var lastLogLoadAt by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var opRunning by remember { mutableStateOf<String?>(null) }
    var pullRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingChannel by remember { mutableStateOf<NewApiChannel?>(null) }
    var pendingRouting by remember { mutableStateOf<Pair<NewApiChannel, String>?>(null) }
    var restoreRoutingPending by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<NewApiModelUsage?>(null) }
    var usageLoading by remember { mutableStateOf(false) }
    var usageError by remember { mutableStateOf<String?>(null) }
    var usageWindow by rememberSaveable { mutableStateOf("1d") }
    var lastUsageLoadAt by remember { mutableStateOf(0L) }
    var probe by remember { mutableStateOf<NewApiProbeResponse?>(null) }
    var balance by remember { mutableStateOf<NewApiBalance?>(null) }
    var workbuddyTogglingUid by remember { mutableStateOf<String?>(null) }
    var workbuddyPendingStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var workbuddyActionError by remember { mutableStateOf<String?>(null) }
    var pipioDetail by remember { mutableStateOf<PipioUsageDetail?>(null) }
    var pipioDetailLoading by remember { mutableStateOf(false) }
    var pipioSheetOpen by remember { mutableStateOf(false) }
    if (pipioSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { pipioSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = OverlaySurface,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("使用日报", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    TextButton(onClick = { pipioSheetOpen = false }) { Text("关闭") }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    if (pipioDetailLoading) Text("正在加载使用日报…", color = Muted)
                    else pipioDetail?.let { PipioUsageChart(it) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    var overviewLoading by remember { mutableStateOf(false) }
    var tokensLoading by remember { mutableStateOf(false) }
    var overviewError by remember { mutableStateOf<String?>(null) }
    var logsError by remember { mutableStateOf<String?>(null) }
    var retryAt by remember { mutableStateOf(0L) }
    var lastTokenLoadAt by remember { mutableStateOf(0L) }
    var logLoadJob by remember { mutableStateOf<Job?>(null) }
    var logRequestKey by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var logRequestId by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun beginOperation(name: String): Boolean {
        if (opRunning != null) return false
        opRunning = name
        return true
    }
    fun endOperation(name: String) { if (opRunning == name) opRunning = null }

    fun saveGatewayCache() {
        GatewayCache.save(context, GatewayCacheSnapshot(
            overview = overview, affinity = affinity, probe = probe,
            models = null, logs = logs, logPage = logPage
        ))
    }

    fun loadFailure(failure: Throwable): String {
        if (failure is GatewayRateLimitedException) {
            retryAt = android.os.SystemClock.elapsedRealtime() + failure.retryAfterSeconds * 1000L
        }
        return failure.message ?: failure.javaClass.simpleName
    }

    fun retryMessage(): String? {
        val remaining = retryAt - android.os.SystemClock.elapsedRealtime()
        return if (remaining > 0) "查询过于频繁，请在 ${(remaining + 999) / 1000} 秒后重试" else null
    }

    fun loadOverview(silent: Boolean = false) = scope.launch {
        if (overviewLoading) return@launch
        retryMessage()?.let { overviewError = it; pullRefreshing = false; return@launch }
        overviewLoading = true
        if (!silent) loading = true
        try {
            val freshOverview = ApiClient.getNewApiGateway()
            val freshAffinity = ApiClient.getNewApiAffinity()
            val freshRoutingPolicy = ApiClient.getNewApiRoutingPolicy()
            val freshProbe = try { ApiClient.getNewApiProbe() } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            val freshBalance = try { ApiClient.getNewApiBalance() } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            overview = freshOverview
            affinity = freshAffinity
            routingPolicy = freshRoutingPolicy
            if (freshProbe != null) probe = freshProbe
            if (freshBalance != null) {
                balance = freshBalance
                val confirmedUids = workbuddyPendingStates.filter { (uid, expectedDisabled) ->
                    freshBalance.workbuddy?.accounts?.firstOrNull { it.uid == uid }?.disabled == expectedDisabled
                }.keys
                if (confirmedUids.isNotEmpty()) {
                    workbuddyPendingStates = workbuddyPendingStates - confirmedUids
                }
            }
            overviewError = if (freshProbe == null) "模型/渠道探针暂时不可用" else null
            if (!silent) saveGatewayCache()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            overviewError = "网关数据加载失败：${loadFailure(t)}"
        } finally {
            overviewLoading = false
            pullRefreshing = false
            if (!silent) {
                loading = false
                pullRefreshing = false
            }
        }
    }
    fun loadPerformance(silent: Boolean = false) = scope.launch {
        if (performanceLoading) return@launch
        retryMessage()?.let { performanceError = it; return@launch }
        performanceLoading = true
        if (!silent) loading = true
        try {
            performance = ApiClient.getNewApiPerformance().models
            performanceError = null
            lastPerformanceLoadAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            performanceError = "模型统计加载失败：${loadFailure(t)}"
        } finally {
            performanceLoading = false
            if (!silent) {
                loading = false
                pullRefreshing = false
            }
        }
    }
    fun loadUsage(silent: Boolean = false) = scope.launch {
        if (usageLoading) return@launch
        retryMessage()?.let { usageError = it; pullRefreshing = false; return@launch }
        usageLoading = true
        if (!silent) loading = true
        try {
            usage = ApiClient.getNewApiModelUsage()
            usageError = null
            lastUsageLoadAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            usageError = "用量统计加载失败：${loadFailure(t)}"
        } finally {
            usageLoading = false
            if (!silent) loading = false
            pullRefreshing = false
        }
    }
    fun toggleWorkBuddyAccount(account: WorkBuddyAccount) = scope.launch {
        if (workbuddyTogglingUid != null) return@launch
        val expectedDisabled = !account.disabled
        val toggleKey = "${account.groupId}:${account.uid}"
        workbuddyTogglingUid = toggleKey
        workbuddyPendingStates = workbuddyPendingStates + (toggleKey to expectedDisabled)
        workbuddyActionError = null
        try {
            ApiClient.setWorkBuddyAccountDisabled(account.uid, expectedDisabled, account.groupId)
            var confirmed = false
            for (attempt in 0 until 10) {
                if (attempt > 0) delay(1_000)
                val snapshot = ApiClient.getNewApiBalance()
                balance = snapshot
                val actualDisabled = snapshot.workbuddy?.accounts
                    ?.firstOrNull { it.groupId == account.groupId && it.uid == account.uid }?.disabled
                if (actualDisabled == expectedDisabled) {
                    confirmed = true
                    break
                }
            }
            workbuddyPendingStates = workbuddyPendingStates - toggleKey
            if (!confirmed) {
                workbuddyActionError = "后台快照尚未同步，已恢复按后台状态显示"
                loadOverview(silent = true)
            }
        } catch (t: Throwable) {
            workbuddyPendingStates = workbuddyPendingStates - toggleKey
            workbuddyActionError = t.message ?: "账号状态切换失败"
        } finally {
            workbuddyTogglingUid = null
        }
    }
    fun loadLogTokens() = scope.launch {
        if (tokensLoading || retryMessage() != null) return@launch
        tokensLoading = true
        try {
            logTokens = ApiClient.getNewApiLogTokens().tokens
            lastTokenLoadAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            loadFailure(t)
        } finally {
            tokensLoading = false
        }
    }
    fun loadLogs(targetPage: Int, silent: Boolean = false, token: String? = logTokenFilter) {
        val requestKey = targetPage to token
        if (logLoadJob?.isActive == true && logRequestKey == requestKey) return
        logLoadJob?.cancel()
        logRequestKey = requestKey
        logRequestId += 1
        retryMessage()?.let { logsError = it; loading = false; pullRefreshing = false; return }
        val requestId = logRequestId
        logLoadJob = scope.launch {
            if (!silent) loading = true
            try {
                val result = ApiClient.getNewApiLogs(targetPage, token = token)
                if (requestId != logRequestId) return@launch
                logs = result
                logPage = targetPage
                lastLogLoadAt = System.currentTimeMillis()
                logsError = null
                if (token == null) saveGatewayCache()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (requestId == logRequestId) logsError = "日志加载失败：${loadFailure(t)}"
            } finally {
                if (requestId == logRequestId) {
                    loading = false
                    pullRefreshing = false
                }
            }
        }
    }
    fun applyChannel(updated: NewApiChannel) {
        val current = overview
        if (current == null) {
            loadOverview()
            return
        }
        overview = current.copy(channels = current.channels.map { if (it.id == updated.id) updated else it })
    }
    LaunchedEffect(Unit) {
        GatewayCache.load(context)?.let { cached ->
            overview = cached.overview
            affinity = cached.affinity
            probe = cached.probe
        }
        loadOverview()
    }
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        val logStale = System.currentTimeMillis() - lastLogLoadAt > 30_000
        if (page == 2 && (logs == null || logStale)) loadLogs(logPage)
        if (page == 2 && System.currentTimeMillis() - lastTokenLoadAt > 60_000) loadLogTokens()
        if (page == 4 && (usage == null || System.currentTimeMillis() - lastUsageLoadAt > 120_000)) loadUsage()
        // 性能页（1）直接消费模型统计；概览（0）的成功率也依赖同一份数据，
        // 因此首次进入这两个页面时按需加载，而不是只在“模型”页加载。
        if (page in listOf(0, 1) && performance == null) loadPerformance(silent = page == 0)
        if (page == 1 && performance != null && System.currentTimeMillis() - lastPerformanceLoadAt > 120_000) loadPerformance(silent = true)
        if (page != 2 && page != 4 && overview == null) loadOverview()
    }
    LaunchedEffect(pagerState.settledPage, lifecycleResumed) {
        // 概览页（余额卡 + 模型池探针）停留期间每 30 秒静默重拉后端快照；
        // 数据新鲜度的上限仍由 NAS 侧 cron 决定（余额 */10、探针 */10）
        if (pagerState.settledPage !in listOf(0, 2) || !lifecycleResumed) return@LaunchedEffect
        while (isActive) {
            delay(30_000)
            if (pagerState.settledPage == 0 && lifecycleResumed) loadOverview(silent = true)
            if (pagerState.settledPage == 2 && lifecycleResumed) {
                loadLogs(logPage, silent = true)
                if (System.currentTimeMillis() - lastTokenLoadAt > 60_000) loadLogTokens()
            }
        }
    }

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            GatewayTopTabs(selected = selectedTab) { target ->
                scope.launch {
                    pagerState.animateScrollToPage(target)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val pullState = rememberPullRefreshState(
                refreshing = pullRefreshing,
                onRefresh = {
                    pullRefreshing = true
                    when (selectedTab) {
                        2 -> { loadLogs(logPage); loadLogTokens() }
                        1 -> loadPerformance()
                        4 -> loadUsage()
                        else -> loadOverview()
                    }
                }
            )
            Box(Modifier.fillMaxSize().pullRefresh(pullState)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it }
            ) { page ->
                when (page) {
                    0 -> GatewayOverviewPage(
                        data = overview,
                        performance = performance,
                        affinity = affinity,
                        probe = probe,
                        balance = balance,
                        workbuddyTogglingUid = workbuddyTogglingUid,
                        workbuddyPendingStates = workbuddyPendingStates,
                        workbuddyActionError = workbuddyActionError,
                        onWorkBuddyToggle = ::toggleWorkBuddyAccount,
                        onPipioClick = { name ->
                            pipioSheetOpen = true
                            pipioDetail = null
                            scope.launch {
                                pipioDetailLoading = true
                                try {
                                    val snapshot = ApiClient.getNewApiBalance()
                                    val report = snapshot.channels.firstOrNull { it.name == name }?.usageDetail
                                    balance = snapshot
                                    pipioDetail = report ?: PipioUsageDetail(name = name, error = "日报暂不可用，请稍后重试")
                                }
                                catch (t: Throwable) {
                                    pipioDetail = PipioUsageDetail(name = name, ok = false, error = t.message ?: "日报加载失败")
                                }
                                finally { pipioDetailLoading = false }
                            }
                        },
                        loading = loading || opRunning != null,
                        onAffinityChange = { enabled ->
                            scope.launch {
                                val operation = "affinity"
                                if (!beginOperation(operation)) return@launch
                                try { affinity = ApiClient.setNewApiAffinity(enabled); error = null; saveGatewayCache() }
                                catch (t: Throwable) { error = "亲和性开关更新失败：${t.message ?: t.javaClass.simpleName}" }
                                finally { endOperation(operation) }
                            }
                        },
                        onClearAffinity = { clearAffinityPending = true }
                    )
                    1 -> GatewayPerformancePage(performance ?: emptyList(), performanceLoading, performanceError)
                    2 -> GatewayLogPage(
                        data = logs,
                        page = logPage,
                        tokens = logTokens,
                        selectedToken = logTokenFilter,
                        updatedAt = lastLogLoadAt,
                        onTokenSelected = { token ->
                            if (token != logTokenFilter) {
                                logTokenFilter = token
                                logs = null
                                logPage = 0
                                lastLogLoadAt = 0L
                                loadLogs(0, token = token)
                            }
                        },
                        onPage = { loadLogs(it) }
                    )
                    3 -> GatewayChannelPage(
                        rows = overview?.channels ?: emptyList(),
                        loading = loading || opRunning != null,
                        policy = routingPolicy,
                        onToggle = { pendingChannel = it },
                        onPolicy = { channel, mode -> pendingRouting = channel to mode },
                        onRestore = { restoreRoutingPending = true },
                        onPriority = { channel, next ->
                            scope.launch {
                                val operation = "priority-${channel.id}"
                                if (!beginOperation(operation)) return@launch
                                try { applyChannel(ApiClient.setNewApiChannelRouting(channel.id, priority = next)); error = null }
                                catch (t: Throwable) { error = "优先级调整失败：${t.message ?: t.javaClass.simpleName}" }
                                finally { endOperation(operation) }
                            }
                        },
                        onWeight = { channel, next ->
                            scope.launch {
                                val operation = "weight-${channel.id}"
                                if (!beginOperation(operation)) return@launch
                                try { applyChannel(ApiClient.setNewApiChannelRouting(channel.id, weight = next)); error = null }
                                catch (t: Throwable) { error = "权重调整失败：${t.message ?: t.javaClass.simpleName}" }
                                finally { endOperation(operation) }
                            }
                        }
                    )
                    else -> GatewayUsagePage(
                        data = usage,
                        window = usageWindow,
                        loading = usageLoading,
                        error = usageError,
                        onWindow = { usageWindow = it },
                        onRetry = { loadUsage() }
                    )
                }
            }
                PullRefreshIndicator(
                    refreshing = pullRefreshing,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = OverlaySurface,
                    contentColor = Blue
                )
            }
            if (loading) {
                Text("正在加载…", color = Muted, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
            }
            (error ?: when (selectedTab) {
                2 -> logsError
                4 -> usageError
                else -> overviewError
            })?.let { GatewayErrorBanner(it) }
        }
    }

    pendingRouting?.let { (channel, mode) ->
        val exclusive = mode == "exclusive"
        CompactConfirmationDialog(
            containerColor = OverlaySurface,
            onDismissRequest = { pendingRouting = null },
            title = { Text(if (exclusive) "确认仅使用此供应商？" else "确认设为最优先？") },
            text = {
                Text(
                    if (exclusive) "${channel.name}\n将保存全部渠道原配置，启用并优先使用该渠道，同时停用其他渠道。目标不可用时请求会直接失败。${if (channel.autoBan != 0) "\n⚠ 当前自动禁用生效，New API 仍可能跳过该渠道。" else ""}\n可随时一键恢复。"
                    else "${channel.name}\n将保存全部渠道原配置，并把该渠道优先级设为最高；其他渠道继续作为故障兜底。${if (channel.autoBan != 0) "\n⚠ 当前自动禁用生效，New API 仍可能跳过该渠道。" else ""}\n可随时一键恢复。"
                )
            },
            confirmButton = {
                TextButton(enabled = opRunning == null, onClick = {
                    scope.launch {
                        val operation = "routing-${channel.id}"
                        if (!beginOperation(operation)) return@launch
                        try {
                            routingPolicy = ApiClient.applyNewApiRoutingPolicy(channel.id, mode)
                            overview = ApiClient.getNewApiGateway()
                            error = null
                        } catch (t: Throwable) {
                            error = "路由策略应用失败：${t.message ?: t.javaClass.simpleName}"
                            routingPolicy = try { ApiClient.getNewApiRoutingPolicy() } catch (_: Throwable) { routingPolicy }
                        } finally { pendingRouting = null; endOperation(operation) }
                    }
                }) { Text(if (exclusive) "确认仅使用" else "确认最优先", color = if (exclusive) Red else Blue) }
            },
            dismissButton = { TextButton(onClick = { pendingRouting = null }) { Text("取消") } }
        )
    }

    if (restoreRoutingPending) {
        CompactConfirmationDialog(
            containerColor = OverlaySurface,
            onDismissRequest = { restoreRoutingPending = false },
            title = { Text("确认恢复原有配置？") },
            text = { Text("将按策略启用前保存的快照，逐项恢复全部渠道的启用状态、优先级和权重，并清理旧亲和性缓存。") },
            confirmButton = {
                TextButton(enabled = opRunning == null, onClick = {
                    scope.launch {
                        val operation = "restore-routing"
                        if (!beginOperation(operation)) return@launch
                        try {
                            routingPolicy = ApiClient.restoreNewApiRoutingPolicy()
                            overview = ApiClient.getNewApiGateway()
                            error = null
                        } catch (t: Throwable) {
                            error = "恢复原有配置失败：${t.message ?: t.javaClass.simpleName}"
                            routingPolicy = try { ApiClient.getNewApiRoutingPolicy() } catch (_: Throwable) { routingPolicy }
                        } finally { restoreRoutingPending = false; endOperation(operation) }
                    }
                }) { Text("确认恢复", color = Blue) }
            },
            dismissButton = { TextButton(onClick = { restoreRoutingPending = false }) { Text("取消") } }
        )
    }

    pendingChannel?.let { channel ->
        CompactConfirmationDialog(
            containerColor = OverlaySurface,
            onDismissRequest = { pendingChannel = null },
            title = { Text("确认${if (channel.status == 1) "停用" else "启用"}渠道？") },
            text = { Text("${channel.name}\n操作后会回读状态。auto_ban 未清除时，渠道仍可能不参与路由。") },
            confirmButton = {
                TextButton(enabled = opRunning == null, onClick = {
                    scope.launch {
                        val operation = "channel-${channel.id}"
                        if (!beginOperation(operation)) return@launch
                        try {
                            overview = ApiClient.setNewApiChannelStatus(channel.id, if (channel.status == 1) 2 else 1)
                            error = null
                        } catch (t: Throwable) {
                            error = "渠道操作失败：${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            pendingChannel = null
                            endOperation(operation)
                        }
                    }
                }) { Text(if (channel.status == 1) "确认停用" else "确认启用", color = if (channel.status == 1) Red else Blue) }
            },
            dismissButton = { TextButton(onClick = { pendingChannel = null }) { Text("取消") } }
        )
    }

    if (clearAffinityPending) {
        CompactConfirmationDialog(
            containerColor = OverlaySurface,
            onDismissRequest = { clearAffinityPending = false },
            title = { Text("确认清理亲和性缓存？") },
            text = { Text("将清除 NAS 主站 New API 的全部渠道亲和性缓存。后续请求会重新选择渠道；不会修改渠道开关、优先级或权重。") },
            confirmButton = {
                TextButton(enabled = opRunning == null, onClick = {
                    scope.launch {
                        val operation = "clear-affinity"
                        if (!beginOperation(operation)) return@launch
                        try { affinity = ApiClient.clearNewApiAffinityCache(); error = null; saveGatewayCache() }
                        catch (t: Throwable) { error = "清理亲和性缓存失败：${t.message ?: t.javaClass.simpleName}" }
                        finally { clearAffinityPending = false; endOperation(operation) }
                    }
                }) { Text("确认清理缓存", color = Red) }
            },
            dismissButton = { TextButton(onClick = { clearAffinityPending = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun GatewayTopTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        gatewayNavItems.forEachIndexed { index, item ->
            val active = index == selected
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                Text(item.label, color = if (active) Blue else Muted, fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) BlueSoft else Color.Transparent)
                        .padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun GatewayOverviewPage(
    data: NewApiGatewayOverview?,
    performance: List<NewApiModelPerformance>?,
    affinity: NewApiAffinityState?,
    probe: NewApiProbeResponse?,
    balance: NewApiBalance?,
    workbuddyTogglingUid: String?,
    workbuddyPendingStates: Map<String, Boolean>,
    workbuddyActionError: String?,
    onWorkBuddyToggle: (WorkBuddyAccount) -> Unit,
    onPipioClick: (String) -> Unit,
    loading: Boolean,
    onAffinityChange: (Boolean) -> Unit,
    onClearAffinity: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (data != null) {
            item {
                val success = performance?.sumOf { it.success } ?: 0
                val failed = performance?.sumOf { it.failed } ?: 0
                val rate = if (success + failed > 0) success.toFloat() / (success + failed) else 0f
                GatewayCard {
                    // 四指标行：24h 请求 / 停用渠道 / 自动封禁 / RPM（New API 滚动 1 分钟请求数）
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GatewayMetricCell("24h 请求", (data.requests24h ?: data.recentRequests).toString(), Blue, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(36.dp).background(Track))
                        GatewayMetricCell("停用渠道", data.disabledChannels.toString(), Amber, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(36.dp).background(Track))
                        GatewayMetricCell("自动封禁", data.autoBannedChannels.toString(), Red, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(36.dp).background(Track))
                        GatewayMetricCell("RPM", data.rpm?.toString() ?: "--", Green, Modifier.weight(1f))
                    }
                    // 整体成功率：依赖按需加载的模型统计，未加载时不占位
                    if (performance != null) {
                        Spacer(Modifier.height(10.dp))
                        Divider(color = Track, thickness = 0.8.dp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("整体成功率", color = Muted, fontSize = 11.sp)
                            Text("${(rate * 100).toInt()}%", color = successColor(rate), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = rate, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(8.dp)), color = successColor(rate), trackColor = Track)
                    }
                    val bal = balance
                    if (bal != null && bal.channels.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Divider(color = Track, thickness = 0.8.dp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("渠道余额", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        bal.channels.forEachIndexed { idx, ch ->
                            if (idx > 0) {
                                Spacer(Modifier.height(6.dp))
                                Divider(color = Track, thickness = 0.7.dp)
                                Spacer(Modifier.height(6.dp))
                            }
                            // WorkBuddy 风格：以每月 1 号余额作为"本月总额"参考，画进度条+剩余%
                            val total = ch.monthStartUsd
                            val pct = if (ch.usd != null && total != null && total > 0) (ch.usd / total).toFloat() else null
                            val clickable = ch.name == "pipio" || ch.name == "pipio2"
                            Row(
                                Modifier.fillMaxWidth().then(if (clickable) Modifier.clickable { onPipioClick(ch.name) } else Modifier),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(ch.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    if (clickable) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("查看日报 ›", color = Blue, fontSize = 10.sp)
                                    }
                                }
                                if (ch.usd != null && total != null) {
                                    Text(
                                        "$${"%.2f".format(ch.usd)} / $${"%.2f".format(total)}",
                                        color = gatewayRatioColor(pct), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                    )
                                } else if (ch.usd != null) {
                                    Text("$${"%.2f".format(ch.usd)}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                } else {
                                    Text("⚠️ 查询失败", color = Amber, fontSize = 11.sp)
                                }
                            }
                            if (pct != null) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = gatewayProgress(pct),
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(8.dp)),
                                    color = gatewayRatioColor(pct), trackColor = Track
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(gatewayRemainingLabel(pct, "月初余额"), color = gatewayRatioColor(pct), fontSize = 10.sp)
                                    if (ch.todayUsedUsd != null) {
                                        Text("今日消耗 $" + "%.2f".format(ch.todayUsedUsd), color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            } else if (ch.todayUsedUsd != null) {
                                Spacer(Modifier.height(2.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Text("今日消耗 $" + "%.2f".format(ch.todayUsedUsd), color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Divider(color = Track, thickness = 0.7.dp)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("合计", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("$${"%.2f".format(bal.total)}", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            val wbPool = balance?.workbuddy
            val wbAt = balance?.updatedAt ?: ""
            val wbErr = balance?.workbuddyError
            if (wbPool != null && wbPool.accounts.isNotEmpty()) {
                item {
                    fun applyPending(account: WorkBuddyAccount): WorkBuddyAccount {
                        val key = "${account.groupId}:${account.uid}"
                        return workbuddyPendingStates[key]?.let { account.copy(disabled = it) } ?: account
                    }
                    val displayedAccounts = wbPool.accounts.map(::applyPending)
                    val displayedGroups = wbPool.groups.map { group ->
                        group.copy(
                            accounts = group.accounts.map(::applyPending),
                            disabled = group.accounts.count { account ->
                                workbuddyPendingStates["${account.groupId}:${account.uid}"] ?: account.disabled
                            }
                        )
                    }
                    val displayedPool = wbPool.copy(
                        accounts = displayedAccounts,
                        groups = displayedGroups,
                        disabled = displayedAccounts.count { it.disabled }
                    )
                    WorkBuddyAccountCard(
                        pool = displayedPool,
                        updatedAt = wbAt,
                        togglingUid = workbuddyTogglingUid,
                        actionError = workbuddyActionError,
                        onToggle = onWorkBuddyToggle
                    )
                }
            } else if (wbErr != null) {
                item {
                    GatewayCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("WorkBuddy 账号", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("⚠️ $wbErr", color = Amber, fontSize = 12.sp)
                    }
                }
            }
        } else {
            item { GatewayEmpty("正在读取网关状态…") }
        }
        item { GatewayProbeCard(probe, affinity, loading, onAffinityChange, onClearAffinity) }
    }
}

@Composable
private fun PipioUsageChart(detail: PipioUsageDetail) {
    GatewayCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${detail.name} 使用日报", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (!detail.ok) {
            Text(detail.error ?: "日报暂不可用", color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GatewayCard
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PipioMetric("今日", detail.today, Modifier.weight(1f))
            PipioMetric("昨日", detail.yesterday, Modifier.weight(1f))
            PipioMetric("近 7 天", detail.week, Modifier.weight(1f))
        }
        val values = detail.daily
        val modelCosts = detail.models.filter { it.quota.isFinite() && it.quota > 0 }.sortedByDescending { it.quota }
        if (modelCosts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("今日模型费用", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            modelCosts.forEach { model ->
                val cacheRate = model.cacheRate?.takeIf { it.isFinite() }
                val cacheRateText = if (cacheRate == null) "缓存率 --" else "缓存率 ${"%.1f".format(Locale.US, cacheRate * 100)}%"
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(model.name, modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp)
                    Text(cacheRateText, modifier = Modifier.weight(1f), color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                    Text(
                        "$${"%.4f".format(model.quota / 500000.0)}",
                        modifier = Modifier.weight(1f),
                        color = Blue,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End
                    )
                }
                LinearProgressIndicator(
                    progress = (model.quota / modelCosts.first().quota).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(RoundedCornerShape(4.dp)),
                    color = Blue, trackColor = Track
                )
            }
        }
        if (values.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("近 7 天消耗（美元）", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.000001)
            Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                values.forEachIndexed { index, value ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text("$${"%.2f".format(value / 500000.0)}", color = Muted, fontSize = 8.sp, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier.fillMaxWidth(0.72f).height((96f * (value / max).toFloat()).coerceAtLeast(4f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (index == values.lastIndex) Blue else Blue.copy(alpha = 0.45f))
                        )
                        Spacer(Modifier.height(4.dp))
                        val dayLabel = SimpleDateFormat("MM/dd", Locale.getDefault()).format(
                            Date((runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(detail.updatedAt.orEmpty())?.time }.getOrNull() ?: System.currentTimeMillis()) - (values.lastIndex - index) * 86_400_000L)
                        )
                        Text(dayLabel, color = Muted, fontSize = 8.sp)
                    }
                }
            }
        }
        if (detail.tokens.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("今日令牌归因", color = Muted, fontSize = 11.sp)
            detail.tokens.take(3).forEach { token ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(token.name, color = Ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$${"%.2f".format(token.quota / 500000.0)} · ${token.calls} 次", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun PipioMetric(label: String, quota: Double?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(if (quota == null) "--" else "$${"%.2f".format(quota / 500000.0)}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun probeStatusLabel(status: String): String = when (status) {
    "healthy" -> "正常"
    "degraded" -> "降级"
    "failed" -> "异常"
    else -> "未知"
}

private fun probeStatusColor(status: String): Color = when (status) {
    "healthy" -> Green
    "degraded" -> Amber
    "failed" -> Red
    else -> Muted
}

private fun probeStatusSurface(status: String): Color = when (status) {
    "healthy" -> GreenSoft
    "degraded" -> AmberSoft
    "failed" -> RedSoft
    else -> CardTint
}

private fun probeProvider(item: NewApiProbeItem): String {
    if (item.id.startsWith("futureppo-grokheavy:")) return "FuturePPO"
    if (item.provider.isNotBlank()) return item.provider
    return when {
        item.id.startsWith("futureppo:") -> "FuturePPO"
        item.id.startsWith("pipio:") -> "Pipio"
        else -> "其他"
    }
}

private fun providerStatus(items: List<NewApiProbeItem>): String {
    if (items.isEmpty()) return "unknown"
    val n = items.size
    val failed = items.count { it.status == "failed" }
    val degraded = items.count { it.status == "degraded" }
    val unknown = items.count { it.status !in setOf("healthy", "degraded", "failed") }
    if (unknown == n) return "unknown"
    if (failed * 2 >= n) return "failed"
    if (failed > 0 || degraded * 2 >= n) return "degraded"
    return "healthy"
}

private fun probeEvidence(item: NewApiProbeItem): String {
    return item.evidence
}

@Composable
private fun ProbeItemRow(item: NewApiProbeItem) {
    val color = probeStatusColor(item.status)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("•", color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(item.name, color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(color = probeStatusSurface(item.status), shape = RoundedCornerShape(6.dp)) {
                Text(probeStatusLabel(item.status), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
        Text(probeEvidence(item), color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp, top = 2.dp))
    }
}

// 账号昵称打码：保留首尾字符，中间用 * 替代
// 纯数字（手机号/UID）：保留前 3 后 2
// 其他（中文/英文混合）：保留前 1 后 1
private fun maskNickname(name: String): String {
    if (name.length <= 2) return name
    return if (name.all { it.isDigit() }) {
        when {
            name.length <= 5 -> name.first() + "****" + name.last()
            else -> name.take(3) + "****" + name.takeLast(2)
        }
    } else {
        name.first() + "****" + name.last()
    }
}

@Composable
private fun WorkBuddyAccountCard(
    pool: WorkBuddyPool,
    updatedAt: String,
    togglingUid: String?,
    actionError: String?,
    onToggle: (WorkBuddyAccount) -> Unit
) {
    var pendingAccount by remember { mutableStateOf<WorkBuddyAccount?>(null) }
    var expandedGroups by remember { mutableStateOf(mutableSetOf<String>()) }
    GatewayCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("WorkBuddy 账号", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("更新 $updatedAt", color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        val summary = buildString {
            append("共 ${pool.total} 个")
            append(" · 可用 ${pool.healthy}")
            if (pool.cooling > 0) append(" · 冷却 ${pool.cooling}")
            if (pool.disabled > 0) append(" · 禁用 ${pool.disabled}")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(summary, color = Muted, fontSize = 11.sp)
            val allAccounts = pool.groups.flatMap { it.accounts }
            val totalCredits = allAccounts.mapNotNull { it.credits }.takeIf { it.size == allAccounts.size }?.sum()
            if (totalCredits != null) {
                Text("余额汇总 ${"%,d".format(totalCredits)}", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        actionError?.let {
            Spacer(Modifier.height(4.dp))
            Text("⚠️ $it", color = Amber, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))

        // 全局判定：有在飞的分组都算正在使用；没有在飞时，只标最近一次请求落点，
        // 避免多个分组各自带着 30 分钟内的历史 last_account 而同时显示「正在使用」。
        val activeInFlightGroups = pool.groups.filter { g -> g.accounts.any { (it.inFlight ?: 0) > 0 } }.map { it.id }.toSet()
        val latestUsedGroupId = if (activeInFlightGroups.isEmpty()) {
            pool.groups
                .mapNotNull { g -> g.lastAccount?.lastTs?.takeIf { it.isNotBlank() }?.let { g.id to it } }
                .maxByOrNull { it.second }?.first
        } else null

        pool.groups.forEach { group ->
            val expanded = group.id in expandedGroups
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = expandedGroups.toMutableSet()
                        if (expanded) next.remove(group.id) else next.add(group.id)
                        expandedGroups = next
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    // 分组标题行直接标注「正在使用」，折叠时也能一眼看出当前走哪个出口。
                    // 在飞 > 0 视为正在处理；否则看最近请求落点（与账号行同一判定口径）。
                    val activeInFlight = group.accounts.sumOf { it.inFlight ?: 0 }
                    val groupActive = group.id in activeInFlightGroups ||
                        (activeInFlightGroups.isEmpty() && group.id == latestUsedGroupId)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (expanded) "▾ ${group.name}" else "▸ ${group.name}",
                            color = if (groupActive) Blue else Ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (groupActive) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (activeInFlight > 0) "正在使用 · 在飞 $activeInFlight" else "正在使用",
                                color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    val groupSummary = buildString {
                        append("账号 ${group.total} 个")
                        append(" · 可用 ${group.healthy}")
                        if (group.cooling > 0) append(" · 冷却 ${group.cooling}")
                        if (group.disabled > 0) append(" · 禁用 ${group.disabled}")
                    }
                    Text(groupSummary, color = Muted, fontSize = 10.sp)
                }
                val groupCredits = group.accounts.mapNotNull { it.credits }.takeIf { it.size == group.accounts.size }?.sum()
                if (groupCredits != null) {
                    Text("${"%,d".format(groupCredits)} 积分", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(2.dp))
                group.accounts.forEachIndexed { idx, acct ->
                    if (idx > 0) {
                        Spacer(Modifier.height(6.dp))
                        Divider(color = Track, thickness = 0.7.dp)
                        Spacer(Modifier.height(6.dp))
                    }
                    val credits = acct.credits
                    val cap = acct.dailyCap
                    val pct = if (credits != null && cap != null && cap > 0) credits.toFloat() / cap else null

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (acct.disabled) "禁用" else if (acct.cooling) "冷却" else "正常",
                                color = if (acct.disabled) Muted else if (acct.cooling) Amber else Green,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(enabled = togglingUid == null) { pendingAccount = acct }
                                    .background(
                                        if (acct.disabled) BlueSoft else if (acct.cooling) AmberSoft else GreenSoft,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(maskNickname(acct.nickname.ifBlank { acct.uid.take(8) }), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            acct.realm?.let {
                                Spacer(Modifier.width(4.dp))
                                Text(it.uppercase(), color = Subtle, fontSize = 9.sp)
                            }
                            // 账号名之后、积分之前：正在处理请求显示「在飞 N」；
                            // 请求刚结束（在飞已归零）时显示「正在使用」，
                            // 避免"明明在用却看不到"。
                            val inFlightNow = acct.inFlight ?: 0
                            val isLastUsed = group.lastAccount?.uid?.isNotBlank() == true &&
                                group.lastAccount?.uid == acct.uid
                            if (inFlightNow > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text("在飞 $inFlightNow", color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            } else if (isLastUsed) {
                                Spacer(Modifier.width(6.dp))
                                Text("正在使用", color = Blue, fontSize = 9.sp)
                            }
                        }
                        if (credits != null && cap != null) {
                            val capNote = if (cap == 0) " · 分母为0，不计算百分比" else ""
                            Text(
                                "${"%,d".format(credits)} / ${"%,d".format(cap)}$capNote",
                                color = creditColor(pct), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        } else if (credits != null) {
                            Text("${"%,d".format(credits)} · 基线缺失", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("积分未知", color = Amber, fontSize = 11.sp)
                        }
                    }
                    if (pct != null) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = pct.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(8.dp)),
                            color = creditColor(pct), trackColor = Track
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("剩余 ${"%.0f".format(pct * 100)}%", color = creditColor(pct), fontSize = 10.sp)
                                if (acct.todayUsed != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("今日 Token 消耗 ${"%,d".format(acct.todayUsed)}", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            val detail = buildString {
                                if (acct.successCount != null) append("成功 ${acct.successCount}")
                                if (acct.breakerFails != null && acct.breakerFails > 0) append(" · 熔断 ${acct.breakerFails}")
                            }
                            if (detail.isNotEmpty()) Text(detail, color = Subtle, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Divider(color = Track, thickness = 0.7.dp)
            Spacer(Modifier.height(6.dp))
        }
    }
    pendingAccount?.let { pending ->
        val current = pool.groups.flatMap { it.accounts }.firstOrNull { it.groupId == pending.groupId && it.uid == pending.uid }
        CompactConfirmationDialog(
            onDismissRequest = { pendingAccount = null },
            title = { Text(if (pending.disabled) "启用账号？" else "禁用账号？") },
            text = { Text("${maskNickname(pending.nickname.ifBlank { pending.uid.take(8) })}：${if (pending.disabled) "启用后可参与网关请求。" else "禁用后不再参与网关请求。"}") },
            confirmButton = {
                TextButton(enabled = togglingUid == null && current != null && current.disabled == pending.disabled, onClick = {
                    pendingAccount = null
                    if (current != null) onToggle(current)
                }) { Text("确认${if (pending.disabled) "启用" else "禁用"}") }
            },
            dismissButton = { TextButton(onClick = { pendingAccount = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun GatewayProbeCard(
    probe: NewApiProbeResponse?,
    affinity: NewApiAffinityState?,
    loading: Boolean,
    onAffinityChange: (Boolean) -> Unit,
    onClearAffinity: () -> Unit
) {
    GatewayCard {
        if (probe == null) {
            Text("模型/模型池探针", color = Ink, fontWeight = FontWeight.SemiBold)
            Text("等待探针结果…", color = Muted, fontSize = 11.sp)
            // 亲和性仍展示，避免 probe 为空时无法操作
            Spacer(Modifier.height(8.dp))
            Divider(color = Track, thickness = 0.8.dp)
            Spacer(Modifier.height(6.dp))
            AffinityRow(affinity, loading, onAffinityChange, onClearAffinity)
            return@GatewayCard
        }
        val monitoredItems = probe.items.filter { it.kind != "task" }
        val taskItems = probe.items.filter { it.kind == "task" }
        val statusItems = if (monitoredItems.isNotEmpty()) monitoredItems else probe.items
        val counts = mapOf(
            "healthy" to statusItems.count { it.status == "healthy" },
            "degraded" to statusItems.count { it.status == "degraded" },
            "failed" to statusItems.count { it.status == "failed" },
            "unknown" to statusItems.count { it.status !in setOf("healthy", "degraded", "failed") }
        )
        val providerGroups = monitoredItems.groupBy { probeProvider(it) }
        val providerStatuses = providerGroups.values.map { providerStatus(it) }
        val status = when {
            providerStatuses.any { it == "failed" } -> "failed"
            providerStatuses.any { it == "degraded" } -> "degraded"
            providerStatuses.any { it == "unknown" } || probe.isStale || probe.probeStatus == "stale" -> "unknown"
            providerGroups.isEmpty() && (counts["unknown"] ?: 0) > 0 -> "unknown"
            else -> "healthy"
        }
        val statusColor = probeStatusColor(status)
        var expandedProviders by remember { mutableStateOf(setOf<String>()) }
        var tasksExpanded by remember { mutableStateOf(false) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("模型/模型池探针", color = Ink, fontWeight = FontWeight.SemiBold)
                Text(probe.checkedAt?.replace("T", " ")?.take(16) ?: "暂无时间", color = Muted, fontSize = 11.sp)
            }
            Surface(color = probeStatusSurface(status), shape = RoundedCornerShape(6.dp)) {
                Text(probeStatusLabel(status), color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        val overallSummary = "正常 ${counts["healthy"]} · 降级 ${counts["degraded"]} · 异常 ${counts["failed"]} · 未知 ${counts["unknown"]}"
        if (providerGroups.size == 1) {
            val (provider, items) = providerGroups.entries.first()
            val groupStatus = providerStatus(items)
            val groupExpanded = provider in expandedProviders
            val canExpand = items.isNotEmpty()
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (canExpand) expandedProviders = if (groupExpanded) expandedProviders - provider else expandedProviders + provider
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(provider, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(overallSummary, color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.width(8.dp))
                Surface(color = probeStatusSurface(groupStatus), shape = RoundedCornerShape(6.dp)) {
                    Text(probeStatusLabel(groupStatus), color = probeStatusColor(groupStatus), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            if (groupExpanded) items.forEach { ProbeItemRow(it) }
        } else {
            Text(overallSummary, color = Muted, fontSize = 11.sp)
            providerGroups.forEach { (provider, items) ->
                val groupStatus = providerStatus(items)
                val groupExpanded = provider in expandedProviders
                val visible = if (groupExpanded) items else emptyList()
                val canExpand = items.isNotEmpty()
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (canExpand) expandedProviders = if (groupExpanded) expandedProviders - provider else expandedProviders + provider
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(provider, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("正常 ${items.count { it.status == "healthy" }} · 降级 ${items.count { it.status == "degraded" }} · 异常 ${items.count { it.status == "failed" }}", color = Muted, fontSize = 10.sp)
                    }
                    Surface(color = probeStatusSurface(groupStatus), shape = RoundedCornerShape(6.dp)) {
                        Text(probeStatusLabel(groupStatus), color = probeStatusColor(groupStatus), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
                if (groupExpanded) {
                    visible.forEach { ProbeItemRow(it) }
                }
            }
        }

        if (taskItems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Divider(color = Track)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { tasksExpanded = !tasksExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("监控任务", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("正常 ${taskItems.count { it.status == "healthy" }} · 需关注 ${taskItems.count { it.status != "healthy" }}", color = Muted, fontSize = 10.sp)
            }
            val visibleTasks = if (tasksExpanded) taskItems else taskItems.filter { it.status != "healthy" }
            if (visibleTasks.isEmpty()) {
                Text("监控任务运行正常", color = Green, fontSize = 10.sp, modifier = Modifier.padding(start = 18.dp, top = 5.dp))
            } else {
                visibleTasks.forEach { ProbeItemRow(it) }
            }
        }
        // 渠道亲和性并入探针卡底部，节省一卡
        Spacer(Modifier.height(10.dp))
        Divider(color = Track, thickness = 0.8.dp)
        Spacer(Modifier.height(6.dp))
        AffinityRow(affinity, loading, onAffinityChange, onClearAffinity)
    }
}

@Composable
private fun AffinityRow(
    affinity: NewApiAffinityState?,
    loading: Boolean,
    onAffinityChange: (Boolean) -> Unit,
    onClearAffinity: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("渠道亲和性", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val detail = when (affinity) {
                null -> "正在读取主站设置…"
                else -> "${affinity.cache.total} 条缓存 · ${affinity.cache.unknown} 条未知"
            }
            Text(detail, color = Muted, fontSize = 11.sp)
        }
        if (affinity != null && affinity.cache.total > 0) {
            TextButton(
                onClick = onClearAffinity,
                enabled = !loading,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) { Text("清理亲和记录", fontSize = 11.sp, color = Blue) }
        }
        Switch(
            checked = affinity?.enabled == true,
            onCheckedChange = onAffinityChange,
            enabled = affinity != null && !loading
        )
    }
}

@Composable
private fun GatewayMetricCell(label: String, value: String, accent: Color, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        Text(label, fontSize = 10.sp, color = Muted, maxLines = 1)
    }
}

@Composable
private fun GatewayPerformancePage(rows: List<NewApiModelPerformance>, loading: Boolean = false, error: String? = null) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (rows.isEmpty()) item { GatewayEmpty(if (loading) "正在加载模型统计…" else (error ?: "暂无最近 24 小时模型请求")) }
        item { Text("近 24 小时 · 按请求量排序", color = Muted, fontSize = 11.sp) }
        items(rows.sortedByDescending { it.requests }) { model ->
            val ratio = (model.successRate / 100).coerceIn(0.0, 1.0).toFloat()
            val accent = successColor(ratio)
            CompactCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.model, color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${"%.1f".format(model.successRate)}% 成功", color = accent, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(buildString {
                    append("${model.requests} 次请求")
                    if (model.failed > 0) append(" · ${model.failed} 次失败")
                    if (model.clientCancelled > 0) append(" · ${model.clientCancelled} 次取消")
                }, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GatewayLogPage(
    data: NewApiLogPage?,
    page: Int,
    tokens: List<String>,
    selectedToken: String?,
    updatedAt: Long,
    onTokenSelected: (String?) -> Unit,
    onPage: (Int) -> Unit
) {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }
    var selectedLog by remember { mutableStateOf<NewApiLogItem?>(null) }
    selectedLog?.let { log ->
        ModalBottomSheet(
            onDismissRequest = { selectedLog = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = OverlaySurface,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("日志详情", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { selectedLog = null }) { Text("关闭") }
                }
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        val fields = listOf(
                            "日志编号" to log.id.toString(),
                            "时间" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(log.createdAt * 1000)),
                            "状态" to logOutcomeMeta(log.outcome).label,
                            "模型" to log.model,
                            "令牌" to log.tokenName,
                            "渠道" to log.channelName,
                            "渠道编号" to log.channelId?.toString().orEmpty(),
                            "请求路径" to log.requestPath,
                            "输入 Token" to log.promptTokens.toString(),
                            "输出 Token" to log.completionTokens.toString(),
                            "费用" to "$${"%.6f".format(log.quota / 500000.0)}",
                            "耗时" to "${log.useTime} 秒",
                            "路由渠道" to log.useChannel.joinToString(" → "),
                            "日志内容" to log.content.ifBlank { "该记录未提供额外内容" }
                        )
                        fields.filter { it.second.isNotBlank() }.forEachIndexed { index, (label, value) ->
                            if (index > 0) Divider(color = Track.copy(alpha = 0.55f), thickness = 0.5.dp)
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                Text(label, modifier = Modifier.width(88.dp), color = Muted, fontSize = 12.sp)
                                Text(value, modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Column {
                Text(if (data?.truncated == true) "最近1小时 · 仅显示最新${data.scanLimit}条" else "最近1小时", color = Muted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedToken == null,
                            onClick = { onTokenSelected(null) },
                            label = { Text("全部令牌", maxLines = 1) }
                        )
                    }
                    items(tokens, key = { it }) { token ->
                        FilterChip(
                            selected = selectedToken == token,
                            onClick = { onTokenSelected(token) },
                            label = { Text(token, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                if (selectedToken != null) {
                    Spacer(Modifier.height(5.dp))
                    Text("当前仅显示「$selectedToken」的调用日志", color = Blue, fontSize = 11.sp)
                }
            }
        }
        if (data == null) item { GatewayEmpty("正在读取使用日志…") }
        items(data?.items ?: emptyList()) { log ->
            val meta = logOutcomeMeta(log.outcome)
            val title = when {
                log.model.isNotBlank() -> log.model
                log.content.isNotBlank() -> log.content
                else -> "管理操作"
            }
            CompactCard(modifier = Modifier.clickable { selectedLog = log }) {
                Text("查看详情 ›", color = Blue, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    GatewayStatusChip(meta.label, meta.ink, meta.surface)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(
                        formatter.format(Date(log.createdAt * 1000)),
                        log.tokenName.ifBlank { "无令牌" },
                        log.channelName.ifBlank { if (log.outcome == "admin") "后台操作" else "未知渠道" }
                    ).joinToString("  ·  "),
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (log.outcome != "admin") {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("↓ ${log.promptTokens}   ↑ ${log.completionTokens}   ", color = Muted, fontSize = 11.sp)
                        Text("耗时 ${log.useTime}s", color = timeColor(log.useTime), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        if (log.reasoningEffort.isNotBlank()) {
                            Text("   推理 ${log.reasoningEffort}", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (data != null) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = page > 0, onClick = { onPage(page - 1) }) { Text("上一页") }
                Text("第 ${page + 1} 页 · ${data.total} 条", color = Muted, fontSize = 12.sp)
                TextButton(enabled = (page + 1) * data.pageSize < data.total, onClick = { onPage(page + 1) }) { Text("下一页") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayUsagePage(
    data: NewApiModelUsage?,
    window: String,
    loading: Boolean,
    error: String?,
    onWindow: (String) -> Unit,
    onRetry: () -> Unit
) {
    val labels = listOf("1d" to "最近 1 天", "7d" to "最近 7 天", "30d" to "最近 30 天")
    val current = data?.windows?.get(window)
    val models = current?.models.orEmpty()
    val requests = models.sumOf { it.requests }
    val tokens = models.sumOf { it.tokens }
    var selectedTrendIndex by remember(window, data) { mutableStateOf<Int?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            GatewayCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("模型用量", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        if (current != null) {
                            Text("${current.startDate} 至 ${current.endDate} · 按请求数排序", color = Muted, fontSize = 10.sp)
                        }
                    }
                    if (data != null) {
                        Text(
                            when {
                                data.stale -> "旧快照"
                                data.source == "sqlite" -> "实时快照"
                                else -> "降级数据"
                            },
                            color = if (data.source == "sqlite" && !data.stale) Green else Amber,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { (key, label) ->
                        FilterChip(
                            selected = window == key,
                            onClick = { onWindow(key) },
                            label = { Text(label, maxLines = 1, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageSummaryMetric("请求数", formatUsageCompact(requests), Blue, Modifier.weight(1f))
                    UsageSummaryMetric("Token 数", formatUsageCompact(tokens), Green, Modifier.weight(1f))
                    UsageSummaryMetric("活跃模型", models.size.toString(), Purple, Modifier.weight(1f))
                }
                if (data?.coverage?.complete == false) {
                    Text("当前为降级分页的部分结果", color = Amber, fontSize = 10.sp)
                }
            }
        }
        if (data == null) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    GatewayEmpty(if (loading) "正在加载用量统计…" else (error ?: "暂无用量数据"))
                    if (!loading) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        } else if (models.isEmpty()) {
            item { GatewayEmpty("该窗口暂无模型调用记录") }
        } else {
            item { UsageDistributionCard(models) }
            item {
                UsageTrendCard(
                    daily = data.daily,
                    startDate = current?.startDate.orEmpty(),
                    selectedIndex = selectedTrendIndex,
                    onSelect = { selectedTrendIndex = it }
                )
            }
        }
    }
}

@Composable
private fun UsageSummaryMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Track.copy(alpha = 0.55f)).padding(10.dp)
    ) {
        Text(
            value, color = color, fontSize = if (value.length > 8) 13.sp else 16.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = Muted, fontSize = 10.sp, maxLines = 1)
    }
}

private fun formatUsageCount(value: Long): String = when {
    value >= 100_000_000L -> "${"%.2f".format(Locale.US, value / 100_000_000.0)} 亿"
    value >= 10_000L -> "${"%.1f".format(Locale.US, value / 10_000.0)} 万"
    else -> "%,d".format(Locale.US, value)
}

private fun formatUsageCompact(value: Long): String = when {
    value >= 100_000_000L -> "${"%.2f".format(Locale.US, value / 100_000_000.0)}亿"
    value >= 10_000L -> "${(value / 10_000.0).roundToInt()}万"
    else -> "%,d".format(Locale.US, value)
}

private val usagePalette = listOf(
    Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444),
    Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF14B8A6)
)

@Composable
private fun UsageDistributionCard(models: List<NewApiUsageModel>) {
    GatewayCard {
        Text("模型分布", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("按请求数占比", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 360.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UsageDonut(models, Modifier.size(192.dp))
                    Spacer(Modifier.width(16.dp))
                    UsageModelTable(models, Modifier.weight(1f))
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    UsageDonut(models, Modifier.size(150.dp))
                    Spacer(Modifier.height(8.dp))
                    UsageModelTable(models, Modifier.fillMaxWidth().heightIn(max = 140.dp))
                }
            }
        }
    }
}

@Composable
private fun UsageDonut(models: List<NewApiUsageModel>, modifier: Modifier) {
    val total = models.sumOf { it.requests }.coerceAtLeast(1L)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 16.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f, (size.height - diameter) / 2f
            )
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            drawArc(Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            var start = -90f
            models.forEachIndexed { index, item ->
                val sweep = 360f * item.requests / total
                if (sweep > 0f) {
                    drawArc(
                        usagePalette[index % usagePalette.size], start, (sweep - 1.2f).coerceAtLeast(0.6f), false,
                        topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt)
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatUsageCount(total), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("请求", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun UsageModelTable(models: List<NewApiUsageModel>, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text("模型", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("请求数", color = Muted, fontSize = 10.sp, modifier = Modifier.width(58.dp), textAlign = TextAlign.End)
            Text("Token 数", color = Muted, fontSize = 10.sp, modifier = Modifier.width(66.dp), textAlign = TextAlign.End)
        }
        Divider(color = Track, thickness = 0.7.dp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 192.dp)) {
            items(models, key = { it.model }) { item ->
                val index = models.indexOf(item)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(usagePalette[index % usagePalette.size]))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.model, color = Ink, fontSize = 11.sp,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(formatUsageCount(item.requests), color = Ink, fontSize = 10.sp, modifier = Modifier.width(58.dp), textAlign = TextAlign.End, maxLines = 1)
                    Text(formatUsageCount(item.tokens), color = Ink, fontSize = 10.sp, modifier = Modifier.width(66.dp), textAlign = TextAlign.End, maxLines = 1)
                }
                Divider(color = Track.copy(alpha = 0.55f), thickness = 0.5.dp)
            }
        }
    }
}

private data class UsageTrendPoint(val label: String, val requests: Long, val tokens: Long)

@Composable
private fun UsageTrendCard(
    daily: List<NewApiUsageDaily>,
    startDate: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit
) {
    val gridColor = if (isDarkTheme()) Color(0xFF374151) else Color(0xFFE5E7EB)
    val points = daily.filter { it.date >= startDate }
        .groupBy { it.date }
        .map { (date, rows) -> UsageTrendPoint(date.takeLast(5), rows.sumOf { it.requests }, rows.sumOf { it.tokens }) }
        .sortedBy { it.label }
    GatewayCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("用量趋势", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("请求数与 Token 变化", color = Muted, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                UsageLegendDot("请求数", usagePalette[0])
                UsageLegendDot("Token", usagePalette[1])
            }
        }
        if (points.isEmpty()) {
            Text("该窗口暂无每日数据", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
            return@GatewayCard
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(192.dp)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(points) {
                    detectTapGestures { offset ->
                        val left = 10.dp.toPx()
                        val right = size.width - 12.dp.toPx()
                        val index = ((offset.x - left) / ((right - left).coerceAtLeast(1f)) * (points.size - 1))
                            .roundToInt().coerceIn(0, points.lastIndex)
                        onSelect(index)
                    }
                }
            ) {
                val left = 10.dp.toPx()
                val right = size.width - 12.dp.toPx()
                val top = 18.dp.toPx()
                val bottom = size.height - 28.dp.toPx()
                val innerWidth = (right - left).coerceAtLeast(1f)
                val innerHeight = (bottom - top).coerceAtLeast(1f)
                val maxRequests = points.maxOf { it.requests }.coerceAtLeast(1L)
                val maxTokens = points.maxOf { it.tokens }.coerceAtLeast(1L)
                for (i in 0..4) {
                    val y = top + innerHeight * i / 4f
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 1.dp.toPx())
                }
                fun coordinates(values: (UsageTrendPoint) -> Long, max: Long): List<androidx.compose.ui.geometry.Offset> =
                    points.mapIndexed { index, point ->
                        androidx.compose.ui.geometry.Offset(
                            left + innerWidth * (if (points.size <= 1) 0f else index.toFloat() / (points.size - 1)),
                            top + innerHeight * (1f - values(point).toFloat() / max)
                        )
                    }

                fun monotonePath(coords: List<androidx.compose.ui.geometry.Offset>): Path {
                    val path = Path()
                    if (coords.isEmpty()) return path
                    path.moveTo(coords.first().x, coords.first().y)
                    if (coords.size == 1) return path
                    val slopes = coords.zipWithNext { first, second ->
                        (second.y - first.y) / (second.x - first.x).coerceAtLeast(0.0001f)
                    }.toMutableList()
                    val tangents = MutableList(coords.size) { 0f }
                    tangents[0] = slopes[0]
                    tangents[coords.lastIndex] = slopes.last()
                    for (index in 1 until coords.lastIndex) {
                        tangents[index] = if (slopes[index - 1] * slopes[index] <= 0f) 0f
                        else (slopes[index - 1] + slopes[index]) / 2f
                    }
                    for (index in 0 until coords.lastIndex) {
                        if (slopes[index] == 0f) {
                            tangents[index] = 0f
                            tangents[index + 1] = 0f
                        } else {
                            val a = tangents[index] / slopes[index]
                            val b = tangents[index + 1] / slopes[index]
                            val h = kotlin.math.sqrt(a * a + b * b)
                            if (h > 3f) {
                                tangents[index] = 3f * slopes[index] * a / h
                                tangents[index + 1] = 3f * slopes[index] * b / h
                            }
                        }
                    }
                    for (index in 0 until coords.lastIndex) {
                        val first = coords[index]
                        val second = coords[index + 1]
                        val third = (second.x - first.x) / 3f
                        path.cubicTo(
                            first.x + third, first.y + tangents[index] * third,
                            second.x - third, second.y - tangents[index + 1] * third,
                            second.x, second.y
                        )
                    }
                    return path
                }

                fun areaPath(coords: List<androidx.compose.ui.geometry.Offset>): Path {
                    val path = monotonePath(coords)
                    if (coords.isNotEmpty()) {
                        path.lineTo(coords.last().x, bottom)
                        path.lineTo(coords.first().x, bottom)
                        path.close()
                    }
                    return path
                }

                val tokenCoords = coordinates({ it.tokens }, maxTokens)
                val requestCoords = coordinates({ it.requests }, maxRequests)
                drawPath(areaPath(tokenCoords), usagePalette[1].copy(alpha = 0.08f))
                drawPath(areaPath(requestCoords), usagePalette[0].copy(alpha = 0.08f))
                drawPath(monotonePath(tokenCoords), usagePalette[1], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(monotonePath(requestCoords), usagePalette[0], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, color = Muted, fontSize = 10.sp)
            if (points.size > 2) Text(points[points.size / 2].label, color = Muted, fontSize = 10.sp)
            Text(points.last().label, color = Muted, fontSize = 10.sp)
        }
        selectedIndex?.let { index ->
            points.getOrNull(index)?.let { point ->
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(point.label, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text("请求 ${formatUsageCount(point.requests)}", color = usagePalette[0], fontSize = 10.sp)
                    Text("Token ${formatUsageCount(point.tokens)}", color = usagePalette[1], fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun UsageLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GatewayChannelPage(
    rows: List<NewApiChannel>,
    loading: Boolean,
    policy: NewApiRoutingPolicy?,
    onToggle: (NewApiChannel) -> Unit,
    onPolicy: (NewApiChannel, String) -> Unit,
    onRestore: () -> Unit,
    onPriority: (NewApiChannel, Int) -> Unit,
    onWeight: (NewApiChannel, Int) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalFloatingNavigationPadding.current),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            RoutingPolicyCard(policy = policy, loading = loading, onRestore = onRestore)
        }
        if (rows.isEmpty()) item { GatewayEmpty("暂无渠道数据") }
        items(rows) { channel ->
            val enabled = channel.status == 1
            CompactCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(channel.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ResponseTimeChip(channel.responseTime)
                            if (channel.autoBan != 0) {
                                Spacer(Modifier.width(6.dp))
                                Text("自动禁用生效中", color = Amber, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    CompactAction(if (enabled) "停用" else "启用", if (enabled) Red else Blue, enabled = !loading && policy != null && !policy.active) { onToggle(channel) }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stepper("优先级", channel.priority, !loading && policy != null && !policy.active, { onPriority(channel, (channel.priority - 1).coerceAtLeast(0)) }, { onPriority(channel, (channel.priority + 1).coerceAtMost(100)) }, Modifier.weight(1f))
                    Stepper("权重", channel.weight, !loading && policy != null && !policy.active, { onWeight(channel, (channel.weight - 1).coerceAtLeast(0)) }, { onWeight(channel, channel.weight + 1) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactAction("设为最优先", Blue, enabled = !loading && policy != null && !policy.active, modifier = Modifier.weight(1f)) { onPolicy(channel, "preferred") }
                    CompactAction("仅使用此供应商", Red, enabled = !loading && policy != null && !policy.active, modifier = Modifier.weight(1f)) { onPolicy(channel, "exclusive") }
                }
            }
        }
    }
}

@Composable
private fun RoutingPolicyCard(policy: NewApiRoutingPolicy?, loading: Boolean, onRestore: () -> Unit) {
    val active = policy?.active == true
    val failed = policy?.phase == "restore_failed"
    GatewayCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("一键路由策略", color = Ink, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        policy == null -> "正在读取当前配置…"
                        failed -> "恢复未完成 · 原配置快照已保留"
                        active -> "${policy.channelName} · ${routingPolicyModeLabel(policy.mode)}"
                        else -> "正常路由 · 未启用临时策略"
                    },
                    color = if (failed) Red else if (active) Blue else Muted,
                    fontSize = 12.sp
                )
            }
            if (active) CompactAction("恢复原有配置", if (failed) Red else Blue, enabled = !loading, onClick = onRestore)
        }
        if (active) {
            Spacer(Modifier.height(6.dp))
            Text("原渠道状态、优先级和权重已保存；恢复成功前不会覆盖快照。", color = Muted, fontSize = 11.sp)
            if (policy?.error?.isNotBlank() == true) {
                Spacer(Modifier.height(4.dp))
                Text(policy.error, color = Red, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIconButton(Icons.Default.Remove, "减少$label", enabled && value > 0, onMinus)
            Text(value.toString(), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            SmallIconButton(Icons.Default.Add, "增加$label", enabled, onPlus)
        }
    }
}

@Composable
private fun SmallIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(if (enabled) BlueSoft else Track), contentAlignment = Alignment.Center) {
                Icon(icon, desc, tint = if (enabled) Blue else Muted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun ResponseTimeChip(timeMs: Long) {
    val (label, ink, surface) = when {
        timeMs <= 0L -> Triple("未测试", Muted, BlueSoft)
        timeMs <= 500L -> Triple("极快", Green, GreenSoft)
        timeMs <= 1000L -> Triple("快速", Green, GreenSoft)
        timeMs <= 2000L -> Triple("一般", Amber, AmberSoft)
        else -> Triple("缓慢", Red, RedSoft)
    }
    StatusBadge(label, when { ink == Green -> "ok"; ink == Amber -> "warn"; ink == Red -> "err"; else -> "info" })
}

@Composable
private fun CompactAction(label: String, color: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) color else Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) color.copy(alpha = 0.12f) else Track)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp)
    )
}

@Composable
private fun GatewayCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FrostCard(modifier = modifier, content = content)
}

@Composable
private fun CompactCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FrostCard(modifier = modifier.fillMaxWidth(), content = content)
}

private data class OutcomeStyle(val label: String, val ink: Color, val surface: Color)
private fun logOutcomeMeta(outcome: String): OutcomeStyle = when (outcome) {
    "success" -> OutcomeStyle("响应正常", Green, GreenSoft)
    "upstream_failed" -> OutcomeStyle("上游失败", Red, RedSoft)
    "client_cancelled" -> OutcomeStyle("客户端取消", Amber, AmberSoft)
    "admin" -> OutcomeStyle("管理操作", Blue, BlueSoft)
    else -> OutcomeStyle("状态未知", Muted, BlueSoft)
}
private fun successColor(value: Float): Color = when {
    value >= 0.9f -> Green
    value >= 0.7f -> Amber
    else -> Red
}

// 积分剩余比例配色：<10% 红、<20% 黄、其余绿（null=未知，用默认墨色）
internal fun gatewayProgress(rawRatio: Float): Float = rawRatio.coerceIn(0f, 1f)

internal fun workBuddyBalanceLabel(credits: Int?, baseline: Int?): String {
    val balanceText = credits?.let { "当前余额 ${"%,d".format(Locale.US, it)}" } ?: "当前余额未知"
    val baselineText = when {
        baseline == null -> "当日基准缺失"
        baseline <= 0 -> "当日基准 ${"%,d".format(Locale.US, baseline)}（不计算比例）"
        else -> "当日基准 ${"%,d".format(Locale.US, baseline)}"
    }
    return "$balanceText · $baselineText"
}

internal fun workBuddyCompactBalanceLabel(credits: Int?, baseline: Int?): String = when {
    credits == null -> "积分未知"
    baseline == null -> "${"%,d".format(Locale.US, credits)} · 基准缺失"
    baseline <= 0 -> "${"%,d".format(Locale.US, credits)} / ${"%,d".format(Locale.US, baseline)}（不计算比例）"
    else -> "${"%,d".format(Locale.US, credits)} / ${"%,d".format(Locale.US, baseline)}"
}

internal fun workBuddyRatioLabel(rawRatio: Float): String = "余额为当日基准的 ${"%.0f".format(Locale.US, (rawRatio * 100).coerceAtLeast(0f))}%"

internal fun gatewayRemainingLabel(rawRatio: Float, baselineLabel: String = "月初余额"): String = if (rawRatio > 1f) {
    "剩余 ${"%.0f".format(rawRatio * 100)}%（已超出$baselineLabel ${"%.0f".format((rawRatio - 1f) * 100)}%）"
} else {
    "剩余 ${"%.0f".format((rawRatio * 100).coerceAtLeast(0f))}%"
}

private fun gatewayRatioColor(pct: Float?): Color = if (pct != null && pct > 1f) StatusAttentionInk else creditColor(pct)

private fun creditColor(pct: Float?): Color = when {
    pct == null -> Ink
    pct < 0.1f -> Red
    pct < 0.2f -> Amber
    else -> Green
}
private fun timeColor(seconds: Long): Color = when {
    seconds < 10L -> Green
    seconds < 30L -> Amber
    else -> Red
}

@Composable
private fun GatewayStatusChip(label: String, ink: Color, surface: Color) {
    StatusBadge(label, when { ink == Green -> "ok"; ink == Amber -> "warn"; ink == Red -> "err"; else -> "info" })
}

@Composable
private fun GatewayEmpty(message: String) {
    Surface(color = BlueSoft, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun GatewayErrorBanner(message: String) {
    Surface(color = RedSoft, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Text(message, color = Red, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
internal fun AirportBoard(instance: MihomoAirportInstance, modifier: Modifier = Modifier) {
    Column(modifier) {
        instance.items.forEachIndexed { index, item ->
            val usedPercent = item.usedPercent.coerceAtLeast(0.0)
            val remainingPercent = (100.0 - usedPercent).coerceIn(0.0, 100.0)
            val color = when (item.level) { "critical" -> Red; "warning" -> Amber; else -> Green }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${item.connections} 连接", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text("剩余 ${"%.1f".format(item.remainingGb)} / ${"%.0f".format(item.totalGb)} GB",
                    color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = (usedPercent / 100.0).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                    color = color, trackColor = Track
                )
                Spacer(Modifier.height(5.dp))
                Text("已用 ${"%.0f".format(usedPercent)}% · 剩余 ${"%.0f".format(remainingPercent)}% · 重置 ${item.resetDays?.let { "${it}天" } ?: "-"}", color = Subtle, fontSize = 10.sp)
            }
            if (index != instance.items.lastIndex) {
                Spacer(Modifier.height(8.dp))
                Divider(color = Track, thickness = 0.7.dp)
                Spacer(Modifier.height(8.dp))
            }
        }
        // 速率与曲线属于整个实例，只在末尾画一次，避免每个机场重复绘制同一份数据。
        if (instance.items.isNotEmpty()) {
            val latest = instance.history.lastOrNull()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("↓ ${formatRateShort(latest?.down?.toDouble() ?: 0.0)}", color = Green, fontSize = 11.sp)
                Text("↑ ${formatRateShort(latest?.up?.toDouble() ?: 0.0)}", color = Amber, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("实例合计", color = Subtle, fontSize = 10.sp)
            }
            Spacer(Modifier.height(5.dp))
            MihomoTrafficChart(instance.history, Modifier.fillMaxWidth().height(34.dp))
        }
    }
}

@Composable
internal fun MihomoTrafficChart(points: List<MihomoTrafficPoint>, modifier: Modifier = Modifier) {
    val down = Green
    val up = Amber
    Canvas(modifier) {
        if (points.size < 2) {
            drawLine(Track, start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = 1.5f)
            return@Canvas
        }
        val maxValue = points.maxOf { maxOf(it.up, it.down) }.coerceAtLeast(1L).toFloat()
        fun pathFor(selector: (MihomoTrafficPoint) -> Long) = androidx.compose.ui.graphics.Path().also { path ->
            points.forEachIndexed { index, point ->
                val x = size.width * index / (points.size - 1).toFloat()
                val y = size.height - (selector(point) / maxValue) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        drawPath(pathFor { it.down }, down, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawPath(pathFor { it.up }, up, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
    }
}
