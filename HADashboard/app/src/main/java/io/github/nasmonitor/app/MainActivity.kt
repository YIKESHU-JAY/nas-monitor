package io.github.nasmonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.nasmonitor.app.ui.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class SetupPayload(val serverUrl: String, val token: String)

private fun parseSetupPayload(raw: String): SetupPayload {
    val uri = Uri.parse(raw.trim())
    require(uri.scheme.equals("nasmonitor", ignoreCase = true) && uri.host.equals("setup", ignoreCase = true)) {
        "二维码不是 NAS Monitor 配置"
    }
    val serverUrl = uri.getQueryParameter("url")?.trim().orEmpty()
    val token = uri.getQueryParameter("token")?.trim().orEmpty()
    require(serverUrl.isNotBlank() && token.isNotBlank()) { "二维码缺少后端地址或访问口令" }
    return SetupPayload(serverUrl, token)
}

private fun setupScanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("扫描 NAS Monitor 配置二维码")
    setBeepEnabled(false)
    setOrientationLocked(false)
}

@Composable
private fun MonitorAuthGate(expired: Boolean, onCancel: (() -> Unit)? = null, onAuthenticated: (String, String) -> Unit) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf(PreferenceHelper.getServerUrl(context)) }
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun verifyConfiguration(rawUrl: String, rawToken: String) {
        val normalized = try {
            ApiClient.normalizeBaseUrl(rawUrl)
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "后端地址格式无效"
            return
        }
        val candidate = rawToken.trim()
        if (candidate.length < 24) {
            error = "请输入有效访问口令"
            return
        }
        verifying = true
        error = null
        scope.launch {
            try {
                ApiClient.verifyServerReachable(normalized)
                ApiClient.verifyApiToken(candidate, normalized)
                ApiClient.setBaseUrl(normalized)
                onAuthenticated(normalized, candidate)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = if (failure.message == "访问口令无效") "访问口令无效" else "验证失败：${failure.message ?: "未知错误"}"
            } finally {
                verifying = false
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        try {
            val payload = parseSetupPayload(contents)
            serverUrl = payload.serverUrl
            token = payload.token
            error = null
            verifyConfiguration(payload.serverUrl, payload.token)
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "二维码格式无效"
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanLauncher.launch(setupScanOptions())
        } else {
            error = "未授予相机权限，无法扫码；你仍可手动输入配置。"
        }
    }

    val baseDensity = LocalDensity.current
    val fontScale = DisplayPreferences.fontScaleOption.scale
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * fontScale)) {
    MaterialTheme {
        Surface(color = AppBg, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(2.dp)) {
                    Column(Modifier.padding(22.dp)) {
                        Text(if (onCancel != null) "更新连接配置" else "配置 NAS Monitor", style = MaterialTheme.typography.titleLarge, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        Text(if (onCancel != null) "原配置保持不变。新地址和口令验证成功后才会保存；取消不会退出登录。" else "请输入你的后端地址和访问口令，或扫描配置二维码。配置仅保存在本机 App 私有存储中。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (expired) Text("口令已失效，请重新验证。", color = StatusCriticalInk, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it; error = null },
                            label = { Text("后端地址") },
                            placeholder = { Text("https://example.com:23245") },
                            singleLine = true,
                            enabled = !verifying,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            isError = error != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; error = null },
                            label = { Text(if (onCancel != null) "新访问口令" else "访问口令") },
                            singleLine = true,
                            enabled = !verifying,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Text(if (tokenVisible) "隐藏口令" else "显示口令")
                                }
                            },
                            isError = error != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    scanLauncher.launch(setupScanOptions())
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            enabled = !verifying,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("扫码导入")
                        }
                        error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            text = if (verifying) "验证中…" else if (onCancel != null) "验证并保存配置" else "进入 Monitor",
                            onClick = { verifyConfiguration(serverUrl, token) },
                            enabled = !verifying && serverUrl.isNotBlank() && token.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (onCancel != null) {
                            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                                Text("取消，保留原配置")
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashReporter.install(applicationContext)
        ApiClient.init(this)
        ServerRegistry.init(this)
        DownloaderStore.init(this)
        CardOrderStore.init(this)
        DisplayPreferences.init(this)
        DataCache.init(this)
        setContent {
            val context = LocalContext.current
            var apiToken by remember { mutableStateOf(PreferenceHelper.getApiToken(context)) }
            var configuredServerUrl by remember { mutableStateOf(PreferenceHelper.getServerUrl(context)) }
            var changingToken by remember { mutableStateOf(false) }
            val expired by ApiClient.authenticationExpired.collectAsState()
            var showExpiredMessage by remember { mutableStateOf(false) }
            LaunchedEffect(expired) {
                if (expired) {
                    changingToken = false
                    PreferenceHelper.setApiToken(context, "")
                    apiToken = ""
                    showExpiredMessage = true
                }
            }
            if (apiToken.isBlank() || configuredServerUrl.isBlank() || expired) {
                MonitorAuthGate(expired = showExpiredMessage || expired, onAuthenticated = { serverUrl, token ->
                    PreferenceHelper.setApiToken(context, token)
                    ApiClient.setApiToken(token)
                    showExpiredMessage = false
                    configuredServerUrl = serverUrl
                    apiToken = token
                })
                return@setContent
            }
            val theme = PreferenceHelper.getTheme(context)
            val accentHex = PreferenceHelper.getAccentColor(context)
            val resolved = if (theme == "system") (if (isSystemInDarkTheme()) "dark" else "light") else theme
            val useDark = isDarkTheme()
            LaunchedEffect(resolved, accentHex) {
                applyTheme(resolved)
                setAccentColor(accentHex)
            }
            val systemUi = rememberSystemUiController()
            SideEffect { systemUi.setSystemBarsColor(Color.Transparent, darkIcons = !useDark) }
            val baseDensity = LocalDensity.current
            val fontScale = DisplayPreferences.fontScaleOption.scale
            MaterialTheme(
                colorScheme = if (useDark) darkColorScheme(primary = Blue, secondary = Purple, tertiary = Green, background = AppBg, surface = OverlaySurface, surfaceVariant = OverlayVariant, onBackground = Ink, onSurface = Ink, onSurfaceVariant = Muted, outline = Track)
                else lightColorScheme(primary = Blue, secondary = Purple, tertiary = Green, background = AppBg, surface = OverlaySurface, surfaceVariant = OverlayVariant, onBackground = Ink, onSurface = Ink, onSurfaceVariant = Muted, outline = Track)
            ) {
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * fontScale)
                ) {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: "overview"
                val managedServers by ServerRegistry.servers.collectAsState()
                LaunchedEffect(Unit) {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        try { ApiClient.getServers() }
                        catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (_: Exception) { }
                        kotlinx.coroutines.delay(15000)
                    }
                }
                LaunchedEffect(entry, managedServers) {
                    val routeNow = entry?.destination?.route
                    val target = when (routeNow) {
                        "server/{serverId}", "ssh/{serverId}", "server-edit/{serverId}" ->
                            entry?.arguments?.getString("serverId")
                        "trends/{host}", "temperature/{host}", "containers/{host}", "services/{host}" ->
                            entry?.arguments?.getString("host")
                        "nas", "aliyun", "tencent" -> routeNow
                        else -> null
                    }
                    if (target != null && managedServers.none { it.id == target }) {
                        nav.navigate("overview") { popUpTo("overview") { inclusive = true }; launchSingleTop = true }
                    }
                }
                val selected = when { route == "alerts" -> 1; route == "gateway" -> 2; route == "tools" -> 3; route == "security" -> 4; route == "settings" -> 5; else -> 0 }
                val mainRoutes = setOf("overview", "alerts", "gateway", "tools", "security", "settings")
                val startupRoute = intent?.getStringExtra("startup_route")
                LaunchedEffect(startupRoute) {
                    if (startupRoute in mainRoutes) {
                        nav.navigate(startupRoute!!) {
                            popUpTo("overview") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
                LiquidGlassHost {
                    if (!isGlassTheme()) ThemeBackdrop()
                    val scaffoldBg by animateColorAsState(targetValue = Color.Transparent, animationSpec = tween(300), label = "scaffoldBg")
                    Scaffold(
                        containerColor = scaffoldBg,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        bottomBar = {
                            if (route in mainRoutes) {
                                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                                    AppBottomBar(selected, nav)
                                }
                            }
                        }
                    ) { inner ->
                    val floatingNavigation = route in mainRoutes
                    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
                    val pagePadding = PaddingValues(
                        start = inner.calculateStartPadding(direction),
                        top = inner.calculateTopPadding(),
                        end = inner.calculateEndPadding(direction),
                        bottom = if (floatingNavigation) 0.dp else inner.calculateBottomPadding()
                    )
                    CompositionLocalProvider(LocalFloatingNavigationPadding provides
                        if (floatingNavigation) inner.calculateBottomPadding() else 0.dp) {
                    Box(Modifier.padding(pagePadding).consumeWindowInsets(inner)) {
                        NavHost(
                            navController = nav,
                            startDestination = "overview",
                            enterTransition = {
                                val mainRoutes = setOf("overview", "alerts", "gateway", "tools", "security", "settings")
                                if (targetState.destination.route in mainRoutes) {
                                    fadeIn(animationSpec = tween(180))
                                } else {
                                    slideInHorizontally(animationSpec = tween(280)) { fullWidth -> fullWidth / 5 } +
                                        fadeIn(animationSpec = tween(200))
                                }
                            },
                            exitTransition = {
                                if (targetState.destination.route in setOf("overview", "alerts", "gateway", "tools", "security", "settings")) {
                                    fadeOut(animationSpec = tween(150))
                                } else {
                                    slideOutHorizontally(animationSpec = tween(240)) { fullWidth -> -fullWidth / 12 } +
                                        fadeOut(animationSpec = tween(160))
                                }
                            },
                            popEnterTransition = {
                                slideInHorizontally(animationSpec = tween(280)) { fullWidth -> -fullWidth / 12 } +
                                    fadeIn(animationSpec = tween(200))
                            },
                            popExitTransition = {
                                slideOutHorizontally(animationSpec = tween(260)) { fullWidth -> fullWidth / 5 } +
                                    fadeOut(animationSpec = tween(160))
                            }
                        ) {
                            composable("overview") { OverviewScreen(nav) }
                            composable("tools") { ToolsScreen(nav) }
                            composable("gateway") { GatewayScreen(nav) }
                            composable("proxy") { ProxyScreen(nav = nav, onBack = { nav.popBackStack() }) }
                            composable("proxy_panel?url={url}") { entry ->
                                val url = entry.arguments?.getString("url") ?: ""
                                ProxyPanelScreen(url = url, onBack = { nav.popBackStack() })
                            }
                            composable("knowledge-archive") { KnowledgeArchiveScreen(nav) }
                            composable("ask") { AskScreen(nav) }
                            composable("alerts") { AlertsScreen(nav) }
                            composable("trends/{host}") { e -> TrendsScreen(host = e.arguments?.getString("host") ?: "nas", onBack = { nav.popBackStack() }) }
                            composable("temperature/{host}") { e -> TemperatureScreen(host = e.arguments?.getString("host") ?: "nas", onBack = { nav.popBackStack() }) }
                            composable("containers/{host}") { entry ->
                                ContainersScreen(host = entry.arguments?.getString("host") ?: "nas", onBack = { nav.popBackStack() })
                            }
                            composable("router") { RouterScreen(onBack = { nav.popBackStack() }) }
                            composable("services/{host}") { entry ->
                                val host = entry.arguments?.getString("host") ?: "nas"
                                ServicesScreen(host = host, onBack = { nav.popBackStack() }, onContainers = { nav.navigate("containers/$host") { launchSingleTop = true } })
                            }
                            composable("disks") { DisksScreen(onBack = { nav.popBackStack() }) }
                            composable("nas") { NasScreen(onBack = { nav.popBackStack() }, onSsh = { nav.navigate("ssh/nas") }, onEdit = { nav.navigate("server-edit/nas") }) }
                            composable("aliyun") { AliyunScreen(onBack = { nav.popBackStack() }, onSsh = { nav.navigate("ssh/aliyun") }, onEdit = { nav.navigate("server-edit/aliyun") }) }
                            composable("servers") { ServerManagementScreen(onBack = { nav.popBackStack() }, onSshConfig = { nav.navigate("server-edit/$it") }) }
                            composable("server/{serverId}") { e ->
                                val id = e.arguments?.getString("serverId") ?: return@composable
                                TencentScreen(onBack = { nav.popBackStack() }, onSsh = { nav.navigate("ssh/$id") }, onEdit = { nav.navigate("server-edit/$id") }, serverId = id)
                            }
                            composable("tencent") { TencentScreen(onBack = { nav.popBackStack() }, onSsh = { nav.navigate("ssh/tencent") }, onEdit = { nav.navigate("server-edit/tencent") }) }
                            composable("security") { SecurityScreen() }
                            composable("downloaders") {
                                DownloadersScreen(
                                    onBack = { nav.popBackStack() },
                                    onOpen = { nav.navigate("downloader/$it") },
                                    onAdd = { nav.navigate("downloader-edit/new") }
                                )
                            }
                            composable("downloader/{serverId}") { e ->
                                val serverId = e.arguments?.getString("serverId") ?: return@composable
                                DownloaderTasksScreen(
                                    serverId = serverId,
                                    onBack = { nav.popBackStack() },
                                    onOpenTorrent = { nav.navigate("torrent/$serverId/$it") },
                                    onEditServer = { nav.navigate("downloader-edit/$serverId") }
                                )
                            }
                            composable("torrent/{serverId}/{torrentId}") { e ->
                                val serverId = e.arguments?.getString("serverId") ?: return@composable
                                val torrentId = e.arguments?.getString("torrentId") ?: return@composable
                                TorrentDetailScreen(serverId, torrentId, onBack = { nav.popBackStack() })
                            }
                            composable("downloader-edit/{serverId}") { e ->
                                val serverId = e.arguments?.getString("serverId")?.takeIf { it != "new" }
                                DownloaderServerEditScreen(
                                    serverId,
                                    onBack = { nav.popBackStack() },
                                    onSaved = { nav.popBackStack() },
                                    onDeleted = { nav.popBackStack() }
                                )
                            }
                            composable("settings") { SettingsScreen(onChangeToken = {
                                changingToken = true
                            }, onManageServers = { nav.navigate("servers") }) }
                            composable("server-edit/{serverId}") { e ->
                                val serverId = e.arguments?.getString("serverId") ?: "nas"
                                ServerConnectionEditScreen(serverId, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                            }
                            composable("ssh/{serverId}") { e ->
                                val serverId = e.arguments?.getString("serverId") ?: "nas"
                                SSHScreen(serverId, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("server-edit/$serverId") })
                            }
                        }
                    }
                    }
                    }
                }
                if (changingToken) {
                    Dialog(onDismissRequest = { changingToken = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        MonitorAuthGate(expired = false, onCancel = { changingToken = false }, onAuthenticated = { serverUrl, token ->
                            if (changingToken && !ApiClient.authenticationExpired.value) {
                                PreferenceHelper.setApiToken(context, token)
                                ApiClient.setApiToken(token)
                                showExpiredMessage = false
                                configuredServerUrl = serverUrl
                                apiToken = token
                                changingToken = false
                            }
                        })
                    }
                }
                }
            }
        }
    }
}
