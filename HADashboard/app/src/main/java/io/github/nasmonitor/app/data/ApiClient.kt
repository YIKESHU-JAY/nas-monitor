package io.github.nasmonitor.app

import android.net.Uri
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class UnauthorizedException : IOException("访问口令无效")
class GatewayRateLimitedException(val retryAfterSeconds: Int) : IOException("查询过于频繁，请在 ${retryAfterSeconds} 秒后重试")

object ApiClient {
    private val expiredState = MutableStateFlow(false)
    val authenticationExpired = expiredState.asStateFlow()
    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 401) {
            synchronized(this) {
                if (apiToken.isNotBlank() && request.header("Authorization") == "Bearer $apiToken") {
                    apiToken = ""
                    expiredState.value = true
                }
            }
            response.close()
            throw UnauthorizedException()
        }
        response
    }
    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val verificationClient = client.newBuilder().apply { interceptors().clear() }.build()

    /** 问答接口使用更长读取超时（AnythingLLM 生成回答可能较慢） */
    private val askClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var configuredBaseUrl = ""
    val baseUrl: String get() = configuredBaseUrl
    @Volatile private var apiToken = ""
    @Volatile private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        configuredBaseUrl = PreferenceHelper.getServerUrl(context)
        setApiToken(PreferenceHelper.getApiToken(context))
    }

    fun normalizeBaseUrl(url: String): String {
        val normalized = url.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "请输入后端地址" }
        val parsed = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("后端地址格式无效")
        require(parsed.scheme?.lowercase() in setOf("http", "https")) { "后端地址必须以 http:// 或 https:// 开头" }
        require(!parsed.host.isNullOrBlank()) { "后端地址缺少有效主机名" }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) { "后端地址不能包含账号、查询参数或片段" }
        require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") { "后端地址不能包含路径" }
        require(parsed.port == -1 || parsed.port in 1..65535) { "后端端口无效" }
        return normalized
    }

    @Synchronized
    fun setBaseUrl(url: String) {
        val normalized = normalizeBaseUrl(url)
        configuredBaseUrl = normalized
        appContext?.let { PreferenceHelper.setServerUrl(it, normalized) }
    }

    @Synchronized
    fun setApiToken(token: String) {
        apiToken = token.trim()
        expiredState.value = false
    }

    suspend fun verifyServerReachable(candidateBaseUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(candidateBaseUrl)
        var lastFailure = "未收到有效响应"
        for (path in listOf("/health", "/api/health")) {
            val request = Request.Builder().url("$normalized$path").get().build()
            try {
                verificationClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 401 || response.code == 403) return@withContext normalized
                    lastFailure = "HTTP ${response.code}"
                }
            } catch (error: IOException) {
                lastFailure = error.message ?: "网络连接失败"
            }
        }
        throw IOException("后端不可达或健康检查失败：$lastFailure")
    }

    suspend fun verifyApiToken(candidate: String, candidateBaseUrl: String = baseUrl) {
        val token = candidate.trim()
        require(token.length >= 24) { "请输入有效访问口令" }
        val normalized = normalizeBaseUrl(candidateBaseUrl)
        val request = Request.Builder().url("$normalized/api/overview").header("Authorization", "Bearer $token").build()
        val response = verificationClient.newCall(request).awaitText()
        gson.fromJson(response, OverviewResponse::class.java) ?: throw IOException("空响应")
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        if (apiToken.isBlank()) throw Exception("需要输入访问口令")
        val request = Request.Builder().url("$baseUrl$path").addHeader("Authorization", "Bearer $apiToken").build()
        if (path.startsWith("/api/newapi/")) return@withContext client.newCall(request).awaitText()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) {
                val retryAfter = response.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 300) ?: 60
                throw GatewayRateLimitedException(retryAfter)
            }
            if (!response.isSuccessful) throw Exception(if (response.code == 401) "访问口令无效" else "HTTP ${response.code}")
            response.body?.string() ?: throw Exception("空响应")
        }
    }

    suspend fun getOverview(): OverviewResponse = withContext(Dispatchers.IO) {
        serverMutex.lock()
        try {
            gson.fromJson(get("/api/overview"), OverviewResponse::class.java).also { result ->
                result.servers?.let { ServerRegistry.update(it) }
            }
        } finally { serverMutex.unlock() }
    }

    private val serverMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun serverRequest(path: String, method: String, payload: Any? = null): String = withContext(Dispatchers.IO) {
        val body = payload?.let { gson.toJson(it).toRequestBody("application/json".toMediaType()) }
        val request = Request.Builder().url("$baseUrl/api/servers$path")
            .header("Authorization", "Bearer $apiToken").method(method, body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { gson.fromJson(raw, com.google.gson.JsonObject::class.java)["error"].asString }.getOrNull()
                throw IOException(message ?: "HTTP ${response.code}")
            }
            raw
        }
    }

    suspend fun getServers(): List<ManagedServer> {
        serverMutex.lock()
        try {
            return gson.fromJson(serverRequest("", "GET"), ServerListResponse::class.java).servers.also { ServerRegistry.update(it) }
        } finally { serverMutex.unlock() }
    }

    suspend fun checkServer(name: String, url: String) {
        serverRequest("/check", "POST", mapOf("name" to name, "glances_url" to url))
    }

    suspend fun addServer(name: String, url: String): ManagedServer {
        serverMutex.lock()
        try {
            val server = gson.fromJson(serverRequest("", "POST", mapOf("name" to name, "glances_url" to url)), ManagedServer::class.java)
            ServerRegistry.update(ServerRegistry.servers.value + server)
            DataCache.invalidate("overview")
            return server
        } finally { serverMutex.unlock() }
    }

    suspend fun deleteServer(id: String) {
        serverMutex.lock()
        try {
            val result = gson.fromJson(serverRequest("/$id", "DELETE"), ServerListResponse::class.java)
            ServerRegistry.update(result.servers)
        } finally { serverMutex.unlock() }
    }

    suspend fun getServerDetail(id: String): AliyunDetailResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/servers/$id/detail"), AliyunDetailResponse::class.java)
    }

    suspend fun getNasDetail(): NasDetailResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/nas"), NasDetailResponse::class.java)
    }

    suspend fun getAliyunDetail(): AliyunDetailResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/aliyun"), AliyunDetailResponse::class.java)
    }

    suspend fun getTencentDetail(): AliyunDetailResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/tencent"), AliyunDetailResponse::class.java)
    }

    suspend fun getTrends(host: String, minutes: Int = 60): TrendResponse = withContext(Dispatchers.IO) {
        val raw = get("/api/trends/$host/$minutes")
        val obj = gson.fromJson(raw, Map::class.java)
        val points = (obj["points"] as? List<Map<String, Any>>)?.map { p ->
            TrendPoint(
                t = parseTrendTimestamp(p["t"]),
                cpu = (p["cpu"] as? Number)?.toFloat() ?: 0f,
                mem = (p["mem"] as? Number)?.toFloat() ?: 0f,
                disk = (p["disk"] as? Number)?.toFloat() ?: 0f,
                rx = (p["rx"] as? Number)?.toFloat() ?: 0f,
                tx = (p["tx"] as? Number)?.toFloat() ?: 0f
            )
        } ?: emptyList()
        TrendResponse(host = obj["host"] as? String ?: host, minutes = (obj["minutes"] as? Number)?.toInt() ?: minutes, points = points)
    }

    private fun parseTrendTimestamp(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() / 1000.0
        }.getOrElse {
            runCatching { java.time.LocalDateTime.parse(value).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000.0 }.getOrDefault(0.0)
        }
        else -> 0.0
    }

    suspend fun getContainers(host: String): DockerData = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/containers?host=$host"), DockerData::class.java)
    }

    suspend fun getTemperatureTrends(host: String, minutes: Int = 60): TemperatureTrendResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/trends/temperature/$host?minutes=$minutes"), TemperatureTrendResponse::class.java)
    }

    suspend fun getContainerStats(host: String): ContainerStatsResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/container/stats/$host"), ContainerStatsResponse::class.java)
    }

    suspend fun getContainerLog(host: String, name: String): String = withContext(Dispatchers.IO) {
        val encodedHost = URLEncoder.encode(host, "UTF-8")
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val raw = get("/api/container/$encodedHost/$encodedName")
        val obj = gson.fromJson(raw, Map::class.java)
        obj["logs"] as? String ?: "无日志"
    }

    suspend fun containerAction(host: String, name: String, action: String): DockerData = withContext(Dispatchers.IO) {
        require(host == "nas") { "仅支持管理 NAS 本机容器" }
        require(action in setOf("start", "stop", "restart")) { "不支持的容器操作" }
        val encodedHost = URLEncoder.encode(host, "UTF-8")
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val encodedAction = URLEncoder.encode(action, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/containers/$encodedHost/$encodedName/$encodedAction")
            .addHeader("Authorization", "Bearer $apiToken")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    gson.fromJson(raw, com.google.gson.JsonObject::class.java)["error"].asString
                }.getOrNull()
                throw IOException(message ?: "HTTP ${response.code}")
            }
            gson.fromJson(raw, com.google.gson.JsonObject::class.java)
                .getAsJsonObject("containers")
                ?.let { gson.fromJson(it, DockerData::class.java) } ?: DockerData()
        }
    }

    @Deprecated("Use getContainerLog(host, name) to preserve host scope")
    suspend fun getContainerLog(name: String): String = getContainerLog("nas", name)

    suspend fun getAirportTraffic(): AirportTrafficResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/airport-traffic"), AirportTrafficResponse::class.java)
    }

    suspend fun getMihomoOverview(): MihomoOverviewResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/mihomo/overview"), MihomoOverviewResponse::class.java)
    }

    suspend fun getMihomoAirports(): MihomoAirportsResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/mihomo/airports"), MihomoAirportsResponse::class.java)
    }

    suspend fun selectMihomoGroup(instance: String, group: String, target: String): MihomoGroupSelectResponse = withContext(Dispatchers.IO) {
        val payload = mapOf("target" to target)
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/mihomo/instances/${Uri.encode(instance)}/groups/${Uri.encode(group)}/select")
            .header("Authorization", "Bearer $apiToken")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { gson.fromJson(raw, com.google.gson.JsonObject::class.java)["error"].asString }.getOrNull()
                throw IOException(message ?: "切换失败 HTTP ${response.code}")
            }
            gson.fromJson(raw, MihomoGroupSelectResponse::class.java)
        }
    }

    suspend fun testMihomoNode(instance: String, name: String): MihomoDelayResponse = withContext(Dispatchers.IO) {
        gson.fromJson(
            get("/api/mihomo/instances/${Uri.encode(instance)}/proxies/${Uri.encode(name)}/delay"),
            MihomoDelayResponse::class.java
        )
    }

    suspend fun getMihomoPanelUrl(instance: String = "core"): MihomoPanelUrl = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/mihomo/panel-url?instance=${Uri.encode(instance)}")
            .header("Authorization", "Bearer $apiToken")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("管理面板暂时不可用")
            gson.fromJson(raw, MihomoPanelUrl::class.java)
        }
    }

    suspend fun getMihomoConnections(instance: String, limit: Int = 100): MihomoConnectionsResponse =
        withContext(Dispatchers.IO) {
            gson.fromJson(
                get("/api/mihomo/instances/connections?instance=${Uri.encode(instance)}&limit=$limit"),
                MihomoConnectionsResponse::class.java
            )
        }

    suspend fun getMihomoRules(instance: String): MihomoRulesResponse = withContext(Dispatchers.IO) {
        gson.fromJson(
            get("/api/mihomo/instances/rules?instance=${Uri.encode(instance)}"),
            MihomoRulesResponse::class.java
        )
    }

    suspend fun getMihomoConfigs(instance: String): MihomoConfigs = withContext(Dispatchers.IO) {
        gson.fromJson(
            get("/api/mihomo/instances/configs?instance=${Uri.encode(instance)}"),
            MihomoConfigs::class.java
        )
    }

    suspend fun getMihomoProviders(instance: String): MihomoProvidersResponse = withContext(Dispatchers.IO) {
        gson.fromJson(
            get("/api/mihomo/instances/providers?instance=${Uri.encode(instance)}"),
            MihomoProvidersResponse::class.java
        )
    }

    suspend fun closeMihomoConnection(instance: String, id: String): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/mihomo/instances/${Uri.encode(instance)}/connections/${Uri.encode(id)}/close", emptyMap())
    }

    suspend fun closeAllMihomoConnections(instance: String): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/mihomo/instances/${Uri.encode(instance)}/connections/close-all", emptyMap())
    }

    suspend fun setMihomoRuleDisabled(instance: String, index: Int, disabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            postJson("/api/mihomo/instances/${Uri.encode(instance)}/rules/disable",
                mapOf("index" to index, "disabled" to disabled))
        }

    suspend fun setMihomoConfig(instance: String, key: String, value: Any): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/mihomo/instances/${Uri.encode(instance)}/config", mapOf("key" to key, "value" to value))
    }

    suspend fun runMihomoProviderAction(instance: String, name: String, action: String): Boolean =
        withContext(Dispatchers.IO) {
            postJson("/api/mihomo/instances/${Uri.encode(instance)}/providers/${Uri.encode(name)}/$action",
                emptyMap())
        }

    private fun postJson(path: String, payload: Map<String, Any?>): Boolean {
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $apiToken")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    gson.fromJson(raw, com.google.gson.JsonObject::class.java)["error"].asString
                }.getOrNull()
                throw IOException(message ?: "操作失败 HTTP ${response.code}")
            }
            return true
        }
    }

    suspend fun getRouterStatus(): RouterStatus = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/router"), RouterStatus::class.java)
    }

    suspend fun getRouterSpeedtestStart(interfaceName: String = "wan1"): SpeedtestResult = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/router/speedtest/start?interface=" + interfaceName), SpeedtestResult::class.java)
    }

    suspend fun getLiveSecurityData(host: String = "nas"): LiveSecurityData = withContext(Dispatchers.IO) {
        val route = if (host == "nas") "/api/security/nas/live" else throw IllegalArgumentException("该主机未接入 NAS 雷池实时安全数据")
        gson.fromJson(get(route), LiveSecurityData::class.java)
    }

    suspend fun getSecurityData(host: String): io.github.nasmonitor.app.ui.SecData = withContext(Dispatchers.IO) {
        val route = when (host) {
            "nas" -> "/api/security/nas"
            "aliyun" -> "/api/security/aliyun"
            "tencent" -> "/api/security/tencent"
            else -> throw IllegalArgumentException("该主机暂未接入安全数据：$host")
        }
        gson.fromJson(get(route), io.github.nasmonitor.app.ui.SecData::class.java)
    }

    @Deprecated("Use getSecurityData(host) to preserve host scope")
    suspend fun getSecurityData(): io.github.nasmonitor.app.ui.SecData = getSecurityData("nas")

    suspend fun getRouterSpeedtestResult(interfaceName: String? = null): SpeedtestResult = withContext(Dispatchers.IO) {
        val path = if (interfaceName.isNullOrBlank()) "/api/router/speedtest/result" else "/api/router/speedtest/result?interface=" + interfaceName
        gson.fromJson(get(path), SpeedtestResult::class.java)
    }

    suspend fun runRouterSpeedtest(interfaceName: String = "wan1"): SpeedtestResult = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/router/speedtest/run?interface=" + interfaceName), SpeedtestResult::class.java)
    }

    suspend fun getRouterSpeedtestHistory(interfaceName: String? = null, limit: Int = 30): SpeedtestHistoryResponse = withContext(Dispatchers.IO) {
        val path = if (interfaceName.isNullOrBlank()) "/api/router/speedtest/history?limit=" + limit else "/api/router/speedtest/history?interface=" + interfaceName + "&limit=" + limit
        gson.fromJson(get(path), SpeedtestHistoryResponse::class.java)
    }

    suspend fun getRouterSpeedtestLatest(): SpeedtestHistoryResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/router/speedtest/latest"), SpeedtestHistoryResponse::class.java)
    }
    suspend fun getNewApiModels(): List<NewApiModelMeta> = withContext(Dispatchers.IO) {
        val root = gson.fromJson(get("/api/newapi/models"), com.google.gson.JsonObject::class.java)
        val arr = root.getAsJsonArray("items") ?: com.google.gson.JsonArray()
        arr.map { gson.fromJson(it, NewApiModelMeta::class.java) }
    }

    suspend fun setNewApiModelStatus(id: Int, status: Int): NewApiModelMeta = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("status" to status)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/models/$id/status").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val root = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            gson.fromJson(root.get("model"), NewApiModelMeta::class.java)
        }
    }

    suspend fun getNewApiProbe(): NewApiProbeResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/probe"), NewApiProbeResponse::class.java)
    }

    suspend fun getNewApiAffinity(): NewApiAffinityState = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/affinity"), NewApiAffinityState::class.java)
    }
    suspend fun getNewApiRoutingPolicy(): NewApiRoutingPolicy = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/routing-policy"), NewApiRoutingPolicy::class.java)
    }
    suspend fun applyNewApiRoutingPolicy(channelId: Int, mode: String): NewApiRoutingPolicy = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("channel_id" to channelId, "mode" to mode)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/routing-policy").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.body?.string().orEmpty().take(240)}")
            val root = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            gson.fromJson(root.get("policy"), NewApiRoutingPolicy::class.java)
        }
    }
    suspend fun restoreNewApiRoutingPolicy(): NewApiRoutingPolicy = withContext(Dispatchers.IO) {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/routing-policy/restore").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.body?.string().orEmpty().take(240)}")
            val root = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            gson.fromJson(root.get("policy"), NewApiRoutingPolicy::class.java)
        }
    }
    suspend fun setNewApiAffinity(enabled: Boolean): NewApiAffinityState = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("enabled" to enabled)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/affinity").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val root = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            gson.fromJson(root.get("affinity"), NewApiAffinityState::class.java)
        }
    }
    suspend fun clearNewApiAffinityCache(): NewApiAffinityState = withContext(Dispatchers.IO) {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/affinity/cache/clear").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val root = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            gson.fromJson(root.get("affinity"), NewApiAffinityState::class.java)
        }
    }

    suspend fun getNewApiGateway(): NewApiGatewayOverview = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/overview"), NewApiGatewayOverview::class.java)
    }
    suspend fun getNewApiPerformance(): NewApiPerformanceResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/performance"), NewApiPerformanceResponse::class.java)
    }
    suspend fun getNewApiBalance(): NewApiBalance = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/balance"), NewApiBalance::class.java)
    }
    suspend fun getPipioUsageDetail(name: String): PipioUsageDetail = withContext(Dispatchers.IO) {
        require(name == "pipio" || name == "pipio2") { "不支持的 Pipio 账号" }
        gson.fromJson(get("/api/newapi/balance/pipio/$name"), PipioUsageDetail::class.java)
    }
    suspend fun setNewApiChannelStatus(id: Int, status: Int): NewApiGatewayOverview = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("status" to status)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/channels/$id/status").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response -> if (!response.isSuccessful) throw Exception("HTTP ${response.code}") }
        getNewApiGateway()
    }

    suspend fun setWorkBuddyAccountDisabled(uid: String, disabled: Boolean, groupId: String = "default") = withContext(Dispatchers.IO) {
        val encodedUid = URLEncoder.encode(uid, "UTF-8")
        val encodedGroup = URLEncoder.encode(groupId, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/workbuddy/groups/$encodedGroup/accounts/$encodedUid/toggle?disabled=$disabled")
            .addHeader("Authorization", "Bearer $apiToken")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty().take(240)
                throw Exception("HTTP ${response.code}: $detail")
            }
        }
    }

    suspend fun setNewApiChannelRouting(id: Int, priority: Int? = null, weight: Int? = null): NewApiChannel = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Int>()
        if (priority != null) payload["priority"] = priority
        if (weight != null) payload["weight"] = weight
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/newapi/channels/$id/routing").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val root = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            gson.fromJson(root.get("channel"), NewApiChannel::class.java)
        }
    }
    suspend fun getNewApiLogTokens(): NewApiLogTokens = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/newapi/log-tokens"), NewApiLogTokens::class.java)
    }
    suspend fun getNewApiLogs(page: Int = 0, pageSize: Int = 30, token: String? = null): NewApiLogPage = withContext(Dispatchers.IO) {
        val tokenQuery = token?.takeIf { it.isNotBlank() }?.let { "&token=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        gson.fromJson(get("/api/newapi/logs?p=$page&page_size=$pageSize$tokenQuery"), NewApiLogPage::class.java)
    }

    /** 把链接交给 AnythingLLM 收藏归档 */
    suspend fun archiveUrl(url: String, metadata: Map<String, String> = emptyMap()): ArchiveTask = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("url" to url, "metadata" to metadata)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/knowledge/archive/url").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            gson.fromJson(response.body?.string() ?: "{}", ArchiveTask::class.java)
        }
    }

    /** 把本地文件上传交给 AnythingLLM 归档 */
    suspend fun archiveFile(fileName: String, bytes: ByteArray): ArchiveTask = withContext(Dispatchers.IO) {
        val fileBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("addToWorkspaces", "nas-docker")
            .addFormDataPart("metadata", "{}")
            .addFormDataPart("file", fileName, fileBody)
            .build()
        val request = Request.Builder().url("$baseUrl/api/knowledge/archive/file").addHeader("Authorization", "Bearer $apiToken").post(multipart).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val result = gson.fromJson(response.body?.string() ?: "{}", com.google.gson.JsonObject::class.java)
            if (result.get("success")?.asBoolean != true) throw IOException("文件归档未成功，请稍后重试")
            gson.fromJson(result, ArchiveTask::class.java)
        }
    }

    /** 向 AnythingLLM 运维知识库提问 */
    suspend fun ask(question: String): AskResponse = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("question" to question)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/ask").addHeader("Authorization", "Bearer $apiToken").post(body).build()
        gson.fromJson(askClient.newCall(request).awaitText(), AskResponse::class.java)
    }

    private suspend fun Call.awaitText(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (it.code == 401) throw UnauthorizedException()
                        if (it.code == 429) {
                            val retryAfter = it.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 300) ?: 60
                            throw GatewayRateLimitedException(retryAfter)
                        }
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        it.body?.string() ?: throw IOException("空响应")
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    /** 查询归档任务状态 */
    suspend fun archiveStatus(taskId: String): ArchiveTask = withContext(Dispatchers.IO) {
        gson.fromJson(get("/api/knowledge/archive/status?task_id=$taskId"), ArchiveTask::class.java)
    }

}
