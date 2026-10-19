@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package io.github.nasmonitor.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.nasmonitor.app.CardOrderStore
import io.github.nasmonitor.app.DisplayPreferences
import io.github.nasmonitor.app.PreferenceHelper
import io.github.nasmonitor.app.ServerRegistry
import io.github.nasmonitor.app.DownloaderStore
import io.github.nasmonitor.app.TaskFieldPreset
import io.github.nasmonitor.app.TaskListDensity
import io.github.nasmonitor.app.data.DownloaderApis
import io.github.nasmonitor.app.data.DownloaderException
import io.github.nasmonitor.app.data.DownloaderProtocol
import io.github.nasmonitor.app.data.DownloaderServer
import io.github.nasmonitor.app.data.TorrentDetail
import io.github.nasmonitor.app.data.TorrentPeer
import io.github.nasmonitor.app.data.TorrentSummary
import io.github.nasmonitor.app.data.TransmissionClient
import io.github.nasmonitor.app.data.TransmissionSession
import io.github.nasmonitor.app.data.TransmissionStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.android.awaitFrame

private val DownloaderPollMs = 4000L

private fun limitLabel(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "不限速" else TransmissionClient.formatSpeed(bytesPerSecond)

private fun friendlyError(error: Throwable): String = when (error) {
    is CancellationException -> ""
    is DownloaderException -> error.message ?: "下载器请求失败"
    else -> error.message ?: "未知错误"
}

@Composable
private fun DownloaderEmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    FrostCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(BlueSoft),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Download, null, tint = Blue, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(message, fontSize = TextCaption, color = Muted, lineHeight = 17.sp)
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(actionLabel, onAction, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun SpeedStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, fontSize = TextTiny, color = Muted)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

// ── 下载器总览 ───────────────────────────────────────────────────────────────

@Composable
fun DownloadersScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val servers by DownloaderStore.servers.collectAsState()
    val cardOrder by CardOrderStore.keys.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<Map<String, TransmissionSession>>(emptyMap()) }
    var stats by remember { mutableStateOf<Map<String, TransmissionStats>>(emptyMap()) }
    var failed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val loadMutex = remember { Mutex() }

    suspend fun load(initial: Boolean) {
        loadMutex.withLock {
            if (initial) loading = true
            val resultSessions = mutableMapOf<String, TransmissionSession>()
            val resultStats = mutableMapOf<String, TransmissionStats>()
            val failures = mutableSetOf<String>()
            coroutineScope {
                servers.forEach { server ->
                    val api = DownloaderApis.of(server)
                    // 会话与统计任一失败即视为该下载器本次不可用，避免“连接失败”与旧速度并存。
                    launch {
                        val result = runCatching {
                            val session = api.session(server)
                            val stat = api.stats(server)
                            session to stat
                        }
                        result.onSuccess { (session, stat) ->
                            resultSessions[server.id] = session
                            resultStats[server.id] = stat
                        }.onFailure { failures += server.id }
                    }
                }
            }
            sessions = resultSessions
            stats = resultStats
            failed = failures
            error = if (servers.isNotEmpty() && failures.size == servers.size) "所有下载器均连接失败，请检查地址、端口和认证设置" else null
            loading = false
        }
    }

    fun refreshNow() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try { load(initial = false) } finally { refreshing = false }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(servers, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            load(initial = true)
            while (true) {
                delay(DownloaderPollMs)
                if (servers.isNotEmpty()) load(initial = false)
            }
        }
    }

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            AppPageHeader("下载器", onBack = onBack) {
                TextButton(onClick = onAdd) { Text("添加", color = Blue, fontWeight = FontWeight.SemiBold) }
            }
        }
    ) { pad ->
        RefreshContainer(refreshing, ::refreshNow, Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalFloatingNavigationPadding.current)
            ) {
                if (servers.isEmpty() && !loading) {
                    item {
                        DownloaderEmptyState(
                            "还没有下载器",
                            "添加 Transmission 或 qBittorrent 服务器后，可在这里查看速度、任务并执行暂停、继续和删除。",
                            "添加下载器",
                            onAdd
                        )
                    }
                }
                if (loading && servers.isNotEmpty()) {
                    item { LoadFeedback("正在连接下载器…") }
                }
                error?.let { item { LoadFeedback(it) } }
                items(CardOrderStore.apply(servers, cardOrder) { "downloader:${it.id}" }, key = { it.id }) { server ->
                    val session = sessions[server.id]
                    val stat = stats[server.id]
                    val isFailed = server.id in failed
                    LiquidAwareCard(
                        Modifier.fillMaxWidth().clickable { onOpen(server.id) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(CardWhite),
                        elevation = MonitorCardElevation(1.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(if (isFailed) RedSoft else BlueSoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isFailed) Icons.Default.ErrorOutline else Icons.Default.Download,
                                        null,
                                        tint = if (isFailed) Red else Blue,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(server.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (isFailed) "连接失败" else buildString {
                                            append(server.protocol.label)
                                            if (!session?.version.isNullOrBlank()) append(" ${session?.version}")
                                        },
                                        fontSize = TextTiny,
                                        color = if (isFailed) Red else Muted,
                                        maxLines = 1
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Muted)
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                SpeedStat("下载", TransmissionClient.formatSpeed(stat?.downloadSpeed ?: 0), Icons.Default.ArrowDownward, Blue)
                                SpeedStat("上传", TransmissionClient.formatSpeed(stat?.uploadSpeed ?: 0), Icons.Default.ArrowUpward, Green)
                                SpeedStat("任务", "${stat?.torrentCount ?: 0}", Icons.AutoMirrored.Filled.List, Ink)
                                SpeedStat("进行中", "${stat?.activeTorrentCount ?: 0}", Icons.Default.PlayArrow, Amber)
                            }
                            if (session?.altSpeedEnabled == true) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "备用限速已开启 · ↓${limitLabel(session.altSpeedDown)} ↑${limitLabel(session.altSpeedUp)}",
                                    fontSize = TextTiny, color = Amber
                                )
                            }
                        }
                    }
                }
                if (servers.isNotEmpty()) {
                    item {
                        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加下载器")
                        }
                    }
                }
            }
        }
    }
}

// ── 下载器任务列表 ───────────────────────────────────────────────────────────

private enum class TorrentFilter(val label: String) {
    ALL("全部"), DOWNLOADING("下载中"), SEEDING("做种中"), PAUSED("已暂停"), ERROR("异常")
}

@Composable
fun DownloaderTasksScreen(serverId: String, onBack: () -> Unit, onOpenTorrent: (String) -> Unit, onEditServer: () -> Unit) {
    val context = LocalContext.current
    val allServers by DownloaderStore.servers.collectAsState()
    val server = remember(serverId, allServers) { allServers.firstOrNull { it.id == serverId } }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var torrents by remember { mutableStateOf<List<TorrentSummary>>(emptyList()) }
    var session by remember { mutableStateOf<TransmissionSession?>(null) }
    var stats by remember { mutableStateOf<TransmissionStats?>(null) }
    var filter by rememberSaveable { mutableStateOf(TorrentFilter.ALL.name) }
    var search by rememberSaveable { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf<TorrentSummary?>(null) }
    var showLimits by remember { mutableStateOf(false) }
    var limitSnapshot by remember { mutableStateOf<TransmissionSession?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val taskDensity = DisplayPreferences.taskDensity
    val taskPreset = DisplayPreferences.taskFieldPreset
    val taskMaxCount = DisplayPreferences.taskMaxCount
    val taskRefreshSeconds = DisplayPreferences.effectiveTaskRefreshSeconds(PreferenceHelper.getRefreshInterval(context))
    val scope = rememberCoroutineScope()
    val loadMutex = remember { Mutex() }

    suspend fun load(initial: Boolean) {
        loadMutex.withLock {
            val target = server
            if (target == null) { loading = false; error = "下载器配置不存在"; return }
            if (initial) loading = true
            try {
                coroutineScope {
                    val api = DownloaderApis.of(target)
                    val list = async { api.torrents(target) }
                    val sess = async { runCatching { api.session(target) }.getOrNull() }
                    val stat = async { runCatching { api.stats(target) }.getOrNull() }
                    torrents = list.await()
                    session = sess.await()
                    stats = stat.await()
                }
                error = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = friendlyError(failure)
            } finally {
                loading = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(serverId, server, lifecycleOwner, taskRefreshSeconds) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            load(initial = true)
            if (taskRefreshSeconds > 0) {
                while (true) {
                    delay(taskRefreshSeconds * 1000L)
                    if (server != null) load(initial = false)
                }
            }
        }
    }

    fun runAction(label: String, block: suspend (DownloaderServer) -> Unit) {
        if (pendingAction != null) return
        val target = server ?: run { actionMessage = "下载器配置不存在"; return }
        pendingAction = label
        scope.launch {
            try {
                block(target)
                actionMessage = "$label 已执行"
                load(initial = false)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                actionMessage = "$label 失败：${friendlyError(failure)}"
            } finally {
                pendingAction = null
            }
        }
    }

    fun openLimits() {
        val snapshot = session
        if (snapshot == null) {
            actionMessage = "尚未获取到限速信息，请稍后重试"
        } else {
            limitSnapshot = snapshot
            showLimits = true
        }
    }

    fun closeSearch() {
        searchOpen = false
        search = ""
        keyboard?.hide()
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            awaitFrame()
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    BackHandler(enabled = searchOpen) { closeSearch() }

    val filteredAll = torrents.filter { torrent ->
        val matchesFilter = when (TorrentFilter.valueOf(filter)) {
            TorrentFilter.ALL -> true
            TorrentFilter.DOWNLOADING -> torrent.status == 4
            TorrentFilter.SEEDING -> torrent.status == 6
            TorrentFilter.PAUSED -> torrent.status == 0
            TorrentFilter.ERROR -> torrent.error > 0
        }
        val query = search.trim()
        matchesFilter && (query.isEmpty() || torrent.name.contains(query, ignoreCase = true))
    }
    val filtered = if (taskMaxCount > 0) filteredAll.take(taskMaxCount) else filteredAll

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            Column {
                AppPageHeader(server?.name ?: "下载器任务", onBack = onBack, subtitle = server?.protocol?.label) {
                    IconButton(onClick = ::openLimits, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Speed,
                            "限速设置",
                            tint = if (session?.altSpeedEnabled == true) Amber else Ink,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            if (searchOpen) "关闭搜索" else "搜索任务",
                            tint = Ink,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onEditServer, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, "服务器设置", tint = Ink, modifier = Modifier.size(22.dp))
                    }
                }
                if (searchOpen) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).focusRequester(searchFocus),
                        singleLine = true,
                        placeholder = { Text("搜索任务名称", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (search.isNotEmpty()) {
                            { IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "清除", modifier = Modifier.size(18.dp)) } }
                        } else null,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            RefreshContainer(refreshing, {
                refreshing = true
                scope.launch { try { load(initial = false) } finally { refreshing = false } }
            }, Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 150.dp + LocalFloatingNavigationPadding.current)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpeedStat("下载", TransmissionClient.formatSpeed(stats?.downloadSpeed ?: 0), Icons.Default.ArrowDownward, Blue)
                            Spacer(Modifier.weight(1f))
                            SpeedStat("上传", TransmissionClient.formatSpeed(stats?.uploadSpeed ?: 0), Icons.Default.ArrowUpward, Green)
                            Spacer(Modifier.weight(1f))
                            SpeedStat("活动", "${stats?.activeTorrentCount ?: 0}/${stats?.torrentCount ?: torrents.size}", Icons.Default.SwapVert, Ink)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TorrentFilter.entries.forEach { entry ->
                                FilterChip(
                                    selected = filter == entry.name,
                                    onClick = { filter = entry.name },
                                    label = {
                                        Text(
                                            entry.label,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BlueSoft,
                                        selectedLabelColor = Blue,
                                        containerColor = if (isGlassTheme()) GlassInnerFill else CardTint,
                                        labelColor = Muted
                                    )
                                )
                            }
                        }
                    }
                    if (loading && torrents.isEmpty()) item { LoadFeedback("正在加载任务…") }
                    error?.let { item { LoadFeedback(it) { scope.launch { load(initial = true) } } } }
                    if (!loading && error == null && filtered.isEmpty()) {
                        item { LoadFeedback(if (torrents.isEmpty()) "这个下载器还没有任务" else "没有符合筛选条件的任务") }
                    }
                    if (taskMaxCount > 0 && filteredAll.size > taskMaxCount) {
                        item { Text("显示前 $taskMaxCount 条，共 ${filteredAll.size} 条；可在设置中调整上限。", fontSize = 11.sp, color = Muted) }
                    }
                    items(filtered, key = { it.id }) { torrent ->
                        TorrentRow(
                            torrent = torrent,
                            busy = pendingAction != null,
                            density = taskDensity,
                            preset = taskPreset,
                            onOpen = { onOpenTorrent(torrent.id) },
                            onToggle = {
                                runAction(if (torrent.isRunning) "暂停" else "开始") { target ->
                                    val api = DownloaderApis.of(target)
                                    if (torrent.isRunning) api.stop(target, listOf(torrent.id))
                                    else api.start(target, listOf(torrent.id))
                                }
                            },
                            onDelete = { showDelete = torrent }
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp + LocalFloatingNavigationPadding.current),
                containerColor = Blue,
                contentColor = OnAccent
            ) { Icon(Icons.Default.Add, "添加任务") }
        }
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            confirmButton = { TextButton(onClick = { actionMessage = null }) { Text("知道了") } },
            title = { Text("操作结果") },
            text = { Text(message) }
        )
    }
    val addTarget = server
    val limitTarget = server
    if (showLimits && limitTarget != null && limitSnapshot != null) {
        LimitSettingDialog(
            session = limitSnapshot,
            busy = pendingAction != null,
            onDismiss = { if (pendingAction == null) showLimits = false },
            onSave = { mode, down, up, altDown, altUp ->
                if (pendingAction != null) return@LimitSettingDialog
                pendingAction = "保存限速"
                scope.launch {
                    try {
                        val api = DownloaderApis.of(limitTarget)
                        api.setSpeedLimits(limitTarget, mode, down, up, altDown, altUp)
                        actionMessage = "限速设置已保存"
                        showLimits = false
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        showLimits = false
                        actionMessage = "保存限速失败：${friendlyError(failure)}\n已重新读取服务端当前值，请重新打开限速设置。"
                    } finally {
                        pendingAction = null
                        load(initial = false)
                    }
                }
            }
        )
    }
    if (showAdd && addTarget != null) {
        AddTorrentDialog(
            defaultDir = session?.downloadDir.orEmpty(),
            busy = adding,
            onDismiss = { if (!adding) showAdd = false },
            onAdd = { magnet, dir, paused ->
                if (adding) return@AddTorrentDialog
                adding = true
                scope.launch {
                    try {
                        DownloaderApis.of(addTarget).addMagnet(addTarget, magnet, dir, paused)
                        showAdd = false
                        actionMessage = "任务已添加"
                        load(initial = false)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        actionMessage = "添加失败：${friendlyError(failure)}"
                    } finally {
                        adding = false
                    }
                }
            }
        )
    }
    showDelete?.let { torrent ->
        var deleteData by remember(torrent.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text("删除任务") },
            text = {
                Column {
                    Text(torrent.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteData = !deleteData },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                        Text("同时删除已下载的文件", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = null
                    runAction("删除") { target -> DownloaderApis.of(target).remove(target, listOf(torrent.id), deleteData) }
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TorrentRow(
    torrent: TorrentSummary,
    busy: Boolean,
    density: TaskListDensity,
    preset: TaskFieldPreset,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    if (density == TaskListDensity.COMPACT) {
        TorrentCompactRow(torrent, busy, preset, onOpen, onToggle, onDelete)
        return
    }
    LiquidAwareCard(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(CardWhite),
        elevation = MonitorCardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(torrent.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(
                            torrent.statusLabel,
                            when {
                                torrent.error > 0 -> RedSoft to Red
                                torrent.status == 6 -> GreenSoft to Green
                                torrent.status == 4 -> BlueSoft to Blue
                                else -> (if (isGlassTheme()) GlassInnerFill else CardTint) to Muted
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        if (preset != TaskFieldPreset.LITE) {
                            Text(TransmissionClient.formatBytes(torrent.totalSize), fontSize = TextTiny, color = Muted)
                        }
                        if (torrent.labels.orEmpty().isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(torrent.labels.orEmpty().take(2).joinToString(" · "), fontSize = TextTiny, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = onToggle, enabled = !busy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (torrent.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (torrent.isRunning) "暂停" else "继续",
                        tint = Blue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, "删除", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = torrent.percentDone.toFloat().coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = if (torrent.error > 0) Red else if (torrent.status == 6) Green else Blue,
                trackColor = Track
            )
            Spacer(Modifier.height(6.dp))
            TorrentFieldLine(torrent, preset)
            if ((preset == TaskFieldPreset.STANDARD || preset == TaskFieldPreset.DOWNLOAD) && torrent.status == 4 && torrent.eta >= 0) {
                Spacer(Modifier.height(2.dp))
                Text("剩余 ${TransmissionClient.formatEta(torrent.eta)}", fontSize = TextTiny, color = Muted, modifier = Modifier.align(Alignment.End))
            }
            if (preset == TaskFieldPreset.SEEDING) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Peers ${torrent.peersConnected}",
                    fontSize = TextTiny, color = Muted, modifier = Modifier.align(Alignment.End)
                )
            }
            if (torrent.error > 0 && !torrent.errorString.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(torrent.errorString.orEmpty(), fontSize = TextTiny, color = Red, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TorrentFieldLine(torrent: TorrentSummary, preset: TaskFieldPreset) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${"%.1f".format(torrent.percentDone * 100)}%", fontSize = TextTiny, color = Ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        when (preset) {
            TaskFieldPreset.LITE -> {
                Text("↓${TransmissionClient.formatSpeed(torrent.rateDownload)}", fontSize = TextTiny, color = Blue)
                Spacer(Modifier.width(8.dp))
                Text("↑${TransmissionClient.formatSpeed(torrent.rateUpload)}", fontSize = TextTiny, color = Green)
            }
            TaskFieldPreset.DOWNLOAD -> {
                Text("↓${TransmissionClient.formatSpeed(torrent.rateDownload)}", fontSize = TextTiny, color = Blue)
                Spacer(Modifier.width(8.dp))
                Text(TransmissionClient.formatBytes(torrent.totalSize), fontSize = TextTiny, color = Muted, maxLines = 1)
            }
            TaskFieldPreset.SEEDING -> {
                Text("分享率 ${TransmissionClient.formatRatio(torrent.uploadRatio)}", fontSize = TextTiny, color = Ink, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text("↑${TransmissionClient.formatBytes(torrent.uploadedEver)}", fontSize = TextTiny, color = Green, maxLines = 1)
            }
            TaskFieldPreset.STANDARD -> {
                Text("↓${TransmissionClient.formatSpeed(torrent.rateDownload)}", fontSize = TextTiny, color = Blue)
                Spacer(Modifier.width(8.dp))
                Text("↑${TransmissionClient.formatSpeed(torrent.rateUpload)}", fontSize = TextTiny, color = Green)
                Spacer(Modifier.width(8.dp))
                Text("分享率 ${TransmissionClient.formatRatio(torrent.uploadRatio)}", fontSize = TextTiny, color = Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TorrentCompactRow(
    torrent: TorrentSummary,
    busy: Boolean,
    preset: TaskFieldPreset,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    LiquidAwareCard(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(CardWhite),
        elevation = MonitorCardElevation(1.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(
                            torrent.statusLabel,
                            when {
                                torrent.error > 0 -> RedSoft to Red
                                torrent.status == 6 -> GreenSoft to Green
                                torrent.status == 4 -> BlueSoft to Blue
                                else -> (if (isGlassTheme()) GlassInnerFill else CardTint) to Muted
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(torrent.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (torrent.status == 4 && torrent.eta >= 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("剩 ${TransmissionClient.formatEta(torrent.eta)}", fontSize = TextTiny, color = Muted, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    TorrentFieldLine(torrent, preset)
                }
                IconButton(onClick = onToggle, enabled = !busy, modifier = Modifier.size(32.dp)) {
                    Icon(if (torrent.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, if (torrent.isRunning) "暂停" else "继续", tint = Blue, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.DeleteOutline, "删除", tint = Muted, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = torrent.percentDone.toFloat().coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = if (torrent.error > 0) Red else if (torrent.status == 6) Green else Blue,
                trackColor = Track
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, colors: Pair<Color, Color>) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(colors.first).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = TextTiny, color = colors.second, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AddTorrentDialog(defaultDir: String, busy: Boolean, onDismiss: () -> Unit, onAdd: (String, String?, Boolean) -> Unit) {
    var magnet by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf(defaultDir) }
    LaunchedEffect(defaultDir) {
        if (dir.isBlank() && defaultDir.isNotBlank()) dir = defaultDir
    }
    var paused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加任务") },
        text = {
            Column {
                OutlinedTextField(
                    value = magnet,
                    onValueChange = { magnet = it; error = null },
                    label = { Text("磁力链接或种子 URL") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = dir,
                    onValueChange = { dir = it },
                    label = { Text("下载目录（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("/downloads") }
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clickable { paused = !paused }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = paused, onCheckedChange = { paused = it })
                    Text("添加后暂停", fontSize = 13.sp)
                }
                error?.let { Text(it, color = Red, fontSize = TextTiny) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val value = magnet.trim()
                    if (!value.startsWith("magnet:", true) && !value.startsWith("http", true)) {
                        error = "请输入磁力链接或 http(s) 种子地址"
                    } else {
                        onAdd(value, dir.ifBlank { null }, paused)
                    }
                }
            ) { Text(if (busy) "添加中…" else "添加", color = Blue, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LimitSettingDialog(
    session: TransmissionSession?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (altMode: Boolean, down: Long, up: Long, altDown: Long, altUp: Long) -> Unit
) {
    fun formatInitial(bytes: Long): String =
        if (bytes <= 0) "0" else ((bytes + 512) / 1024).coerceAtLeast(1).toString()
    var altMode by remember(session?.altSpeedEnabled) { mutableStateOf(session?.altSpeedEnabled == true) }
    var down by remember(session?.speedLimitDown) { mutableStateOf(formatInitial(session?.speedLimitDown ?: 0)) }
    var up by remember(session?.speedLimitUp) { mutableStateOf(formatInitial(session?.speedLimitUp ?: 0)) }
    var altDown by remember(session?.altSpeedDown) { mutableStateOf(formatInitial(session?.altSpeedDown ?: 0)) }
    var altUp by remember(session?.altSpeedUp) { mutableStateOf(formatInitial(session?.altSpeedUp ?: 0)) }
    var error by remember { mutableStateOf<String?>(null) }
    fun parse(value: String): Long? = value.trim().ifBlank { "0" }.toLongOrNull()?.takeIf { it >= 0 }

    fun quickFill(kbps: Long) {
        if (busy) return
        error = null
        if (altMode) {
            altDown = kbps.toString()
            altUp = kbps.toString()
        } else {
            down = kbps.toString()
            up = kbps.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("限速设置") },
        text = {
            Column {
                Text("当前模式", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "常规限速", true to "备用限速").forEach { (value, label) ->
                        FilterChip(
                            selected = altMode == value,
                            onClick = { if (!busy) altMode = value },
                            label = {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BlueSoft,
                                selectedLabelColor = Blue,
                                containerColor = if (isGlassTheme()) GlassInnerFill else CardTint,
                                labelColor = Muted
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("常规限速（KiB/s，0 表示不限速）", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LimitInput("下载", down, { down = it; error = null }, Modifier.weight(1f), enabled = !busy)
                    LimitInput("上传", up, { up = it; error = null }, Modifier.weight(1f), enabled = !busy)
                }
                Spacer(Modifier.height(12.dp))
                Text("备用限速（KiB/s，0 表示不限速）", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LimitInput("下载", altDown, { altDown = it; error = null }, Modifier.weight(1f), enabled = !busy)
                    LimitInput("上传", altUp, { altUp = it; error = null }, Modifier.weight(1f), enabled = !busy)
                }
                Spacer(Modifier.height(12.dp))
                Text("常用限额", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to "不限", 512L to "512", 1024L to "1M", 2048L to "2M", 5120L to "5M").forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { quickFill(value) },
                            enabled = !busy,
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 11.sp, maxLines = 1) }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Red, fontSize = TextTiny)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && session != null,
                onClick = {
                    val d = parse(down)
                    val u = parse(up)
                    val ad = parse(altDown)
                    val au = parse(altUp)
                    if (d == null || u == null || ad == null || au == null) {
                        error = "请输入大于等于 0 的整数"
                    } else {
                        onSave(altMode, d, u, ad, au)
                    }
                }
            ) { Text(if (busy) "保存中…" else "保存", color = Blue, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun LimitInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> if (raw.all { it.isDigit() } && raw.length <= 9) onChange(raw) },
        enabled = enabled,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = RoundedCornerShape(10.dp)
    )
}

// ── 任务详情 ─────────────────────────────────────────────────────────────────

private enum class DetailTab(val label: String) { OVERVIEW("概览"), FILES("文件"), TRACKERS("Tracker"), PEERS("Peers") }

@Composable
fun TorrentDetailScreen(serverId: String, torrentId: String, onBack: () -> Unit) {
    val allServers by DownloaderStore.servers.collectAsState()
    val server = remember(serverId, allServers) { allServers.firstOrNull { it.id == serverId } }
    var detail by remember { mutableStateOf<TorrentDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(DetailTab.OVERVIEW.name) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadMutex = remember { Mutex() }

    suspend fun load(initial: Boolean) {
        loadMutex.withLock {
            val target = server
            if (target == null) { loading = false; error = "下载器配置不存在"; return }
            if (initial) loading = true
            try {
                detail = DownloaderApis.of(target).torrent(target, torrentId)
                notFound = detail == null
                if (notFound) error = "任务不存在或已被删除"
                else error = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = friendlyError(failure)
            } finally {
                loading = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(serverId, torrentId, server, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            load(initial = true)
            while (!notFound && server != null) {
                delay(DownloaderPollMs)
                if (notFound || server == null) break
                load(initial = false)
            }
        }
    }

    fun action(label: String, block: suspend (DownloaderServer) -> Unit) {
        if (pendingAction != null) return
        val target = server ?: run { message = "下载器配置不存在"; return }
        pendingAction = label
        scope.launch {
            try {
                block(target)
                message = "$label 已执行"
                load(initial = false)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                message = "$label 失败：${friendlyError(failure)}"
            } finally {
                pendingAction = null
            }
        }
    }

    Scaffold(
        containerColor = PageBackground,
        topBar = { AppPageHeader(detail?.name ?: "任务详情", onBack = onBack) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                ContentSizedTabs(DetailTab.entries.map { it.label }, DetailTab.valueOf(tab).ordinal) { tab = DetailTab.entries[it].name }
                if (loading && detail == null) LoadFeedback("正在加载任务详情…")
                error?.let { LoadFeedback(it) { scope.launch { load(initial = true) } } }
                detail?.let { item ->
                    when (DetailTab.valueOf(tab)) {
                        DetailTab.OVERVIEW -> OverviewTab(item)
                        DetailTab.FILES -> FilesTab(item)
                        DetailTab.TRACKERS -> TrackersTab(item)
                        DetailTab.PEERS -> PeersTab(item)
                    }
                }
            }
            if (detail != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = OverlaySurface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val torrent = detail ?: return@Row
                        DetailAction(if (torrent.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, if (torrent.isRunning) "暂停" else "继续", pendingAction == null) {
                            action(if (torrent.isRunning) "暂停" else "继续") { target ->
                                val api = DownloaderApis.of(target)
                                if (torrent.isRunning) api.stop(target, listOf(torrentId))
                                else api.start(target, listOf(torrentId))
                            }
                        }
                        DetailAction(Icons.Default.Refresh, "校验", pendingAction == null) {
                            action("校验") { target -> DownloaderApis.of(target).verify(target, listOf(torrentId)) }
                        }
                        DetailAction(Icons.Default.Sync, "汇报", pendingAction == null) {
                            action("汇报") { target -> DownloaderApis.of(target).reannounce(target, listOf(torrentId)) }
                        }
                        DetailAction(Icons.Default.DeleteOutline, "删除", pendingAction == null) {
                            confirmDelete = true
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        var deleteData by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除任务") },
            text = {
                Column {
                    Text(detail?.name ?: "这个任务", maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteData = !deleteData },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                        Text("同时删除已下载的文件", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val target = server
                    if (target == null) { message = "下载器配置不存在"; return@TextButton }
                    pendingAction = "删除"
                    scope.launch {
                        try {
                            DownloaderApis.of(target).remove(target, listOf(torrentId), deleteData)
                            onBack()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            message = "删除失败：${friendlyError(failure)}"
                        } finally {
                            pendingAction = null
                        }
                    }
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
            title = { Text("操作结果") },
            text = { Text(text) }
        )
    }
}

@Composable
private fun DetailAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (enabled) Blue else Muted, modifier = Modifier.size(20.dp))
        Text(label, fontSize = TextTiny, color = if (enabled) Ink else Muted)
    }
}

@Composable
private fun OverviewTab(torrent: TorrentDetail) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp + LocalFloatingNavigationPadding.current)
    ) {
        item {
            FrostCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(torrent.statusLabel, when {
                        torrent.error > 0 -> RedSoft to Red
                        torrent.status == 6 -> GreenSoft to Green
                        torrent.status == 4 -> BlueSoft to Blue
                        else -> (if (isGlassTheme()) GlassInnerFill else CardTint) to Muted
                    })
                    Spacer(Modifier.width(8.dp))
                    Text("${"%.1f".format(torrent.percentDone * 100)}%", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.weight(1f))
                    Text(TransmissionClient.formatBytes(torrent.totalSize), fontSize = TextCaption, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = torrent.percentDone.toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (torrent.error > 0) Red else if (torrent.status == 6) Green else Blue,
                    trackColor = Track
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SpeedStat("下载", TransmissionClient.formatSpeed(torrent.rateDownload), Icons.Default.ArrowDownward, Blue)
                    SpeedStat("上传", TransmissionClient.formatSpeed(torrent.rateUpload), Icons.Default.ArrowUpward, Green)
                    SpeedStat("剩余", if (torrent.status == 4) TransmissionClient.formatEta(torrent.eta) else "—", Icons.Default.Schedule, Muted)
                }
                if (torrent.error > 0 && !torrent.errorString.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(torrent.errorString.orEmpty(), fontSize = TextCaption, color = Red)
                }
            }
        }
        item {
            FrostCard(Modifier.fillMaxWidth()) {
                InfoRow("分享率", TransmissionClient.formatRatio(torrent.uploadRatio))
                InfoRow("已上传", TransmissionClient.formatBytes(torrent.uploadedEver))
                InfoRow("已下载", TransmissionClient.formatBytes(torrent.downloadedEver))
                InfoRow("下载目录", torrent.downloadDir.ifBlank { "—" })
                InfoRow("添加时间", formatTimestamp(torrent.addedDate))
                InfoRow("完成时间", if (torrent.doneDate > 0) formatTimestamp(torrent.doneDate) else "—")
                InfoRow("连接", "${torrent.peersConnected} 个 Peer · ↓${torrent.peersSendingToUs} ↑${torrent.peersGettingFromUs}")
                if (!torrent.labels.isNullOrEmpty()) InfoRow("标签", torrent.labels.orEmpty().joinToString(" · "))
                if (!torrent.creator.isNullOrBlank()) InfoRow("创建者", torrent.creator.orEmpty())
                if (!torrent.comment.isNullOrBlank()) InfoRow("备注", torrent.comment.orEmpty())
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = TextCaption, color = Muted, modifier = Modifier.width(78.dp))
        Text(value, fontSize = TextCaption, color = Ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatTimestamp(seconds: Long): String {
    if (seconds <= 0) return "—"
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(seconds * 1000))
}

@Composable
private fun FilesTab(torrent: TorrentDetail) {
    val files = torrent.files.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp + LocalFloatingNavigationPadding.current)
    ) {
        item { Text("${files.size} 个文件", fontSize = TextCaption, color = Muted) }
        items(files.size) { index ->
            val file = files[index]
            val progress = if (file.length > 0) file.bytesCompleted.toDouble() / file.length else 0.0
            FrostCard(Modifier.fillMaxWidth()) {
                Text(file.name.substringAfterLast('/'), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(file.name, fontSize = TextTiny, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = progress.toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Blue, trackColor = Track
                )
                Spacer(Modifier.height(4.dp))
                Text("${TransmissionClient.formatBytes(file.bytesCompleted)} / ${TransmissionClient.formatBytes(file.length)}", fontSize = TextTiny, color = Muted)
            }
        }
        if (files.isEmpty()) item { LoadFeedback("没有文件信息") }
    }
}

@Composable
private fun TrackersTab(torrent: TorrentDetail) {
    val stats = torrent.trackerStats.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp + LocalFloatingNavigationPadding.current)
    ) {
        if (stats.isEmpty()) item { LoadFeedback("没有 Tracker 信息") }
        items(stats, key = { it.id }) { TrackerRow(it) }
    }
}

@Composable
private fun TrackerRow(stat: io.github.nasmonitor.app.data.TrackerStat) {
    FrostCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(if (stat.lastAnnounceSucceeded) Green else Amber)
            )
            Spacer(Modifier.width(8.dp))
            Text(stat.host.ifBlank { stat.announce }, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                if (stat.seederCount >= 0) append("种子 ${stat.seederCount} · ")
                if (stat.leecherCount >= 0) append("下载 ${stat.leecherCount} · ")
                if (stat.downloadCount >= 0) append("完成 ${stat.downloadCount}")
            }.trimEnd(' ', '·'),
            fontSize = TextTiny, color = Muted
        )
        if (!stat.lastAnnounceResult.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(stat.lastAnnounceResult.orEmpty(), fontSize = TextTiny, color = if (stat.lastAnnounceSucceeded) Muted else Red, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PeersTab(torrent: TorrentDetail) {
    val peers = torrent.peers.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp + LocalFloatingNavigationPadding.current)
    ) {
        item { Text("${peers.size} 个已连接 Peer", fontSize = TextCaption, color = Muted) }
        if (peers.isEmpty()) item { LoadFeedback("当前没有已连接的 Peer") }
        items(peers) { peer ->
            FrostCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${peer.address}:${peer.port}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
                    if (peer.isEncrypted) StatusBadge("加密", GreenSoft to Green)
                }
                Spacer(Modifier.height(4.dp))
                Text(peer.clientName.ifBlank { "未知客户端" }, fontSize = TextTiny, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "进度 ${"%.1f".format(peer.progress * 100)}% · ↓${TransmissionClient.formatSpeed(peer.rateToClient)} ↑${TransmissionClient.formatSpeed(peer.rateToPeer)} · ${if (peer.isIncoming) "入站" else "出站"}",
                    fontSize = TextTiny, color = Muted
                )
            }
        }
    }
}

// ── 服务器编辑 ───────────────────────────────────────────────────────────────

@Composable
fun DownloaderServerEditScreen(serverId: String?, onBack: () -> Unit, onSaved: (String) -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(serverId) { serverId?.let { DownloaderStore.find(it) } }
    var protocol by rememberSaveable(serverId) { mutableStateOf((existing?.protocol ?: DownloaderProtocol.TRANSMISSION).name) }
    var name by rememberSaveable(serverId) { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable(serverId) { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable(serverId) { mutableStateOf((existing?.port ?: DownloaderProtocol.TRANSMISSION.defaultPort).toString()) }
    var username by rememberSaveable(serverId) { mutableStateOf(existing?.username ?: "") }
    var password by remember(serverId) { mutableStateOf(existing?.let { DownloaderStore.password(context, it.id) }.orEmpty()) }
    var passwordTouched by remember(serverId) { mutableStateOf(false) }
    var useHttps by rememberSaveable(serverId) { mutableStateOf(existing?.useHttps ?: false) }
    var allowInsecure by rememberSaveable(serverId) { mutableStateOf(existing?.allowInsecure ?: false) }
    var rpcPath by rememberSaveable(serverId) { mutableStateOf(existing?.rpcPath ?: DownloaderProtocol.TRANSMISSION.defaultPath) }
    var passwordVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentServer(): DownloaderServer = DownloaderServer(
        id = existing?.id ?: "",
        name = name,
        host = host,
        port = port.toIntOrNull() ?: 0,
        username = username,
        useHttps = useHttps,
        allowInsecure = allowInsecure,
        rpcPath = rpcPath,
        protocol = DownloaderProtocol.fromStorage(protocol)
    )

    fun save() {
        val server = currentServer()
        try {
            DownloaderApis.of(server).validate(server)
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "配置无效"
            return
        } catch (failure: Exception) {
            error = failure.message ?: "配置无效"
            return
        }
        saving = true
        error = null
        try {
            val saved = DownloaderStore.save(
                context,
                server,
                password,
                previousId = existing?.id,
                passwordChanged = passwordTouched
            )
            onSaved(saved.id)
        } catch (failure: Exception) {
            error = failure.message ?: "保存失败"
        } finally {
            saving = false
        }
    }

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            AppPageHeader(if (existing == null) "添加下载器" else "编辑下载器", onBack = onBack) {
                TextButton(onClick = ::save, enabled = !saving) {
                    Text(if (saving) "保存中…" else "保存", color = Blue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 24.dp + LocalFloatingNavigationPadding.current),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloaderProtocol.entries.forEach { entry ->
                    FilterChip(
                        selected = protocol == entry.name,
                        onClick = {
                            if (protocol != entry.name) {
                                val previous = DownloaderProtocol.fromStorage(protocol)
                                if (port == previous.defaultPort.toString()) port = entry.defaultPort.toString()
                                if (rpcPath == previous.defaultPath) rpcPath = entry.defaultPath
                                protocol = entry.name
                                testResult = null
                                error = null
                            }
                        },
                        label = { Text(entry.label, fontSize = 12.sp) }
                    )
                }
            }
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(if (DownloaderProtocol.fromStorage(protocol) == DownloaderProtocol.QBITTORRENT) "例如：NAS qBittorrent" else "例如：NAS Transmission") })
            OutlinedTextField(value = host, onValueChange = { host = it; error = null }, label = { Text("地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("192.0.2.21 或 https://nas.example.com") })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rpcPath,
                    onValueChange = { rpcPath = it },
                    label = { Text(if (DownloaderProtocol.fromStorage(protocol) == DownloaderProtocol.QBITTORRENT) "API 路径" else "RPC 路径") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f)
                )
            }
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; passwordTouched = true },
                label = { Text("密码（可选）") },
                placeholder = { Text(if (existing != null) "留空并保存可清除已保存的密码" else "") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "隐藏" else "显示", fontSize = 12.sp) }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().clickable { useHttps = !useHttps }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useHttps, onCheckedChange = { useHttps = it })
                Text("使用 HTTPS", fontSize = 13.sp)
            }
            if (!useHttps) {
                Text("未启用 HTTPS：用户名和密码会以明文 HTTP 传输，建议只在可信内网使用。", fontSize = TextTiny, color = Amber)
            }
            if (useHttps) {
                Row(Modifier.fillMaxWidth().clickable { allowInsecure = !allowInsecure }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                    Text("忽略证书校验（仅自签名证书时使用）", fontSize = 13.sp)
                }
                if (allowInsecure) {
                    Text("已关闭证书链与主机名校验，连接可能被中间人截获，请确认网络可信。", fontSize = TextTiny, color = Red)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val server = currentServer()
                        try { DownloaderApis.of(server).validate(server) } catch (failure: Exception) { error = failure.message; return@OutlinedButton }
                        testing = true
                        testResult = null
                        error = null
                        scope.launch {
                            try {
                                testResult = DownloaderApis.of(server).test(
                                    server.copy(id = existing?.id ?: "test"),
                                    passwordOverride = password
                                )
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                error = "连接失败：${friendlyError(failure)}"
                            } finally {
                                testing = false
                            }
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (testing) "测试中…" else "测试连接") }
                PrimaryButton("保存", ::save, Modifier.weight(1f), enabled = !saving)
            }
            testResult?.let { Text(it, color = Green, fontSize = TextCaption) }
            error?.let { Text(it, color = Red, fontSize = TextCaption) }
            if (existing != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("删除这个下载器", color = Red, fontSize = 13.sp)
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除下载器") },
            text = { Text("仅移除本机保存的连接配置，不会影响下载器服务器上的任务。") },
            confirmButton = {
                TextButton(onClick = {
                    DownloaderStore.remove(context, existing.id)
                    // 移除已删除下载器在混合排序中的 key。
                    CardOrderStore.retain(
                        (ServerRegistry.servers.value.map { "server:${it.id}" } +
                            DownloaderStore.servers.value.map { "downloader:${it.id}" }).toSet() -
                            "downloader:${existing.id}"
                    )
                    confirmDelete = false
                    onDeleted()
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}
