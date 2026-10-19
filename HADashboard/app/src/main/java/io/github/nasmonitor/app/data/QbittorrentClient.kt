package io.github.nasmonitor.app.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * qBittorrent WebAPI v2 适配。任务 ID 使用 info hash。
 * 认证使用 SID Cookie；服务端开启子网免密白名单时可留空用户名。
 */
object QbittorrentApi : DownloaderApi {
    private val gson = Gson()
    private val clients = ConcurrentHashMap<Boolean, OkHttpClient>()
    private val sids = ConcurrentHashMap<String, String>()

    /**
     * qBittorrent 的 TorrentID 参数为 40 位 hex：v1 用 info hash，v2/hybrid 用截断后的 v2 hash。
     * 参考 `BitTorrent::TorrentID::fromString` 与 `Digest32<160>::Data(QString)`。
     */
    private val hashPattern = Regex("^[0-9a-fA-F]{40}$")

    private fun normalizeHash(id: String): String? =
        id.trim().takeIf { hashPattern.matches(it) }?.lowercase()

    /** OkHttp 异步执行，让协程取消能真正中断在途请求。 */
    private suspend fun call(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    private fun hashQuery(hash: String): String =
        "hash=" + java.net.URLEncoder.encode(hash, "UTF-8")

    private fun pathWithHash(path: String, hash: String): String = "$path?${hashQuery(hash)}"

    override fun endpoint(server: DownloaderServer): String {
        val rawHost = server.host.trim()
        require(rawHost.isNotEmpty()) { "服务器地址不能为空" }
        val hasScheme = rawHost.contains("://")
        require(!hasScheme || rawHost.startsWith("http://", true) || rawHost.startsWith("https://", true)) {
            "服务器地址只支持 http:// 或 https://"
        }
        val normalized = when {
            hasScheme -> rawHost
            server.useHttps -> "https://$rawHost"
            else -> "http://$rawHost"
        }.trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("服务器地址格式无效")
        val scheme = uri.scheme?.lowercase()
        require(scheme in setOf("http", "https")) { "服务器地址必须以 http:// 或 https:// 开头" }
        require(!uri.host.isNullOrBlank()) { "服务器地址缺少有效主机名" }
        require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
        val hostPart = when {
            uri.port in 1..65535 -> "${uri.host}:${uri.port}"
            hasScheme -> uri.host
            else -> {
                require(server.port in 1..65535) { "端口需在 1-65535 之间" }
                "${uri.host}:${server.port}"
            }
        }
        val path = server.rpcPath.trim().let {
            when {
                it.isEmpty() -> "/api/v2"
                it.startsWith("/") -> it
                else -> "/$it"
            }
        }.trimEnd('/')
        return "$scheme://$hostPart$path"
    }

    override fun displayAddress(server: DownloaderServer): String {
        val rawHost = server.host.trim()
        if (rawHost.contains("://")) {
            val uri = runCatching { URI(rawHost.trimEnd('/')) }.getOrNull() ?: return rawHost
            return if (uri.port > 0) "${uri.scheme}://${uri.host}:${uri.port}" else (uri.scheme?.let { "$it://${uri.host}" } ?: rawHost)
        }
        val scheme = if (server.useHttps) "https://" else "http://"
        return "$scheme${rawHost.trimEnd('/')}:${server.port}"
    }

    override fun validate(server: DownloaderServer) {
        require(server.name.isNotBlank()) { "请填写服务器名称" }
        require(server.host.isNotBlank()) { "请填写服务器地址" }
        require(server.port in 1..65535) { "端口需在 1-65535 之间" }
        endpoint(server)
    }

    private fun client(insecure: Boolean): OkHttpClient = clients.getOrPut(insecure) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
        if (insecure) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            builder.sslSocketFactory(context.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

    private fun base(server: DownloaderServer) = endpoint(server)

    private fun origin(server: DownloaderServer): String = displayAddress(server)

    private suspend fun login(server: DownloaderServer, passwordOverride: String?): Boolean {
        if (server.username.isBlank()) return false
        val password = passwordOverride ?: DownloaderAuth.password(server)
        val body = FormBody.Builder()
            .add("username", server.username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url("${base(server)}/auth/login")
            .header("Referer", origin(server))
            .post(body)
            .build()
        val response = try {
            call(client(server.allowInsecure), request)
        } catch (error: IOException) {
            throw DownloaderException("无法连接 ${displayAddress(server)}：${error.message ?: "网络错误"}", error)
        }
        response.use { resp ->
            if (resp.code == 403) throw DownloaderException("用户名或密码错误，或 IP 已被封禁")
            if (!resp.isSuccessful) throw DownloaderException("qBittorrent 登录失败：HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty().trim()
            if (body.equals("Fails.", ignoreCase = true)) {
                throw DownloaderException("用户名或密码错误")
            }
            val sid = resp.headers.values("Set-Cookie")
                .firstOrNull { it.startsWith("SID=") }
                ?.substringAfter("SID=")
                ?.substringBefore(';')
                ?.takeIf { it.isNotBlank() }
                ?: throw DownloaderException("qBittorrent 未返回 SID，请检查 WebUI 设置")
            sids[server.id] = sid
            return true
        }
    }

    private suspend fun request(
        server: DownloaderServer,
        path: String,
        method: String = "GET",
        body: RequestBody? = null,
        passwordOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = "${base(server)}$path"
        var attempt = 0
        while (attempt < 2) {
            val builder = Request.Builder()
                .url(url)
                .header("Referer", origin(server))
                .header("Accept", "application/json")
            sids[server.id]?.let { builder.header("Cookie", "SID=$it") }
            if (body != null) builder.method(method, body) else builder.method(method, null)
            val response = try {
                call(client(server.allowInsecure), builder.build())
            } catch (error: IOException) {
                throw DownloaderException("无法连接 ${displayAddress(server)}：${error.message ?: "网络错误"}", error)
            }
            response.use { resp ->
                if (resp.code == 403 && attempt == 0) {
                    val loggedIn = login(server, passwordOverride)
                    if (!loggedIn) {
                        throw DownloaderException(
                            if (server.username.isBlank()) "qBittorrent 需要登录，请填写用户名和密码" else "qBittorrent 认证失败"
                        )
                    }
                    attempt++
                    return@use
                }
                if (resp.code == 401) throw DownloaderException("用户名或密码错误")
                if (!resp.isSuccessful) throw DownloaderException("qBittorrent 返回 HTTP ${resp.code}")
                return@withContext resp.body?.string().orEmpty()
            }
        }
        throw DownloaderException("qBittorrent 认证失败，请重试")
    }

    private suspend fun json(server: DownloaderServer, path: String, method: String = "GET", body: RequestBody? = null): JsonElement {
        val text = request(server, path, method, body)
        return runCatching { gson.fromJson(text, JsonElement::class.java) }.getOrNull()
            ?: throw DownloaderException("qBittorrent 返回内容不是有效 JSON")
    }

    private fun form(vararg pairs: Pair<String, String>): RequestBody =
        FormBody.Builder().apply { pairs.forEach { (key, value) -> add(key, value) } }.build()

    private fun hashParam(ids: List<String>): String = ids.joinToString("|")

    override suspend fun session(server: DownloaderServer, passwordOverride: String?): DownloaderSession {
        val prefs = json(server, "/app/preferences").takeIf { it.isJsonObject }?.asJsonObject
            ?: throw DownloaderException("qBittorrent 偏好设置解析失败")
        val version = runCatching { request(server, "/app/version").trim() }.getOrDefault("")
        // 限速模式是保存时的关键判断依据，读取失败不能静默当成常规模式。
        val altMode = request(server, "/transfer/speedLimitsMode").trim() == "1"
        // qBittorrent preferences 的 dl_limit / alt_dl_limit 均为字节/秒；会话统一保存字节/秒。
        return DownloaderSession(
            version = version,
            rpcVersion = 0,
            downloadDir = prefs.string("save_path"),
            altSpeedEnabled = altMode,
            altSpeedDown = prefs.long("alt_dl_limit"),
            altSpeedUp = prefs.long("alt_up_limit"),
            speedLimitDown = prefs.long("dl_limit"),
            speedLimitUp = prefs.long("up_limit"),
            speedLimitDownEnabled = prefs.long("dl_limit") > 0,
            speedLimitUpEnabled = prefs.long("up_limit") > 0
        )
    }

    override suspend fun stats(server: DownloaderServer): DownloaderStats {
        val transfer = json(server, "/transfer/info").takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val list = json(server, "/torrents/info").takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        // server_state 提供磁盘剩余与队列；旧版本/权限受限时降级为空对象，不影响主统计。
        val main = runCatching {
            json(server, "/sync/maindata?rid=0").takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
        val serverState = main?.get("server_state")?.takeIf { it.isJsonObject }?.asJsonObject
        var active = 0
        var paused = 0
        var errors = 0
        list.forEach { element ->
            val state = element.takeIf { it.isJsonObject }?.asJsonObject?.string("state").orEmpty()
            if (state in runningStates) active++
            if (state in pausedStates) paused++
            if (state == "error" || state == "missingFiles") errors++
        }
        return DownloaderStats(
            downloadSpeed = transfer.long("dl_info_speed"),
            uploadSpeed = transfer.long("up_info_speed"),
            activeTorrentCount = active,
            pausedTorrentCount = paused,
            torrentCount = list.size(),
            // 优先使用 server_state 的历史累计；旧版本不返回 alltime 字段时回退到本次会话累计。
            totalDownloaded = serverState?.long("alltime_dl")?.takeIf { it > 0 } ?: transfer.long("dl_info_data"),
            totalUploaded = serverState?.long("alltime_ul")?.takeIf { it > 0 } ?: transfer.long("up_info_data"),
            freeSpace = serverState?.long("free_space_on_disk")?.takeIf { it > 0 } ?: -1,
            errorTorrentCount = errors,
            queuedIoCount = serverState?.int("queued_io_jobs") ?: -1
        )
    }

    override suspend fun torrents(server: DownloaderServer): List<TorrentSummary> {
        val array = json(server, "/torrents/info").takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.map { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw DownloaderException("任务列表解析失败")
            summary(obj)
        }
    }

    override suspend fun torrent(server: DownloaderServer, id: String): TorrentDetail? {
        val hash = normalizeHash(id) ?: return null
        val props = runCatching {
            json(server, pathWithHash("/torrents/properties", hash)).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return null
        val info = runCatching {
            json(server, "${pathWithHash("/torrents/info", hash)}&hashes=${java.net.URLEncoder.encode(hash, "UTF-8")}").takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
        val files = runCatching {
            json(server, pathWithHash("/torrents/files", hash)).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull()
        val trackers = runCatching {
            json(server, pathWithHash("/torrents/trackers", hash)).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull()
        val peers = runCatching {
            json(server, "${pathWithHash("/sync/torrentPeers", hash)}&rid=0")
                .takeIf { it.isJsonObject }?.asJsonObject
                ?.get("peers")?.takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

        val progress = info?.double("progress") ?: 0.0
        val totalSize = props.long("total_size").takeIf { it > 0 } ?: (info?.long("total_size") ?: 0L)
        val state = info?.string("state").orEmpty()
        val mapped = mapState(state)
        val trackerStats = trackers?.mapIndexedNotNull { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val url = obj.string("url")
            if (url.isBlank()) return@mapIndexedNotNull null
            TrackerStat(
                id = index,
                host = runCatching { URI(url).host }.getOrNull().orEmpty(),
                announce = url,
                lastAnnounceResult = obj.string("msg").takeIf { it.isNotBlank() },
                lastAnnounceSucceeded = obj.int("status") == 2,
                seederCount = obj.int("num_seeds"),
                leecherCount = obj.int("num_leeches"),
                downloadCount = obj.int("num_downloaded")
            )
        }.orEmpty()

        val peerList = peers?.entrySet()?.mapNotNull { (key, value) ->
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val address = obj.string("ip").ifBlank { key.substringBeforeLast(':', key) }
            val port = obj.int("port").takeIf { it > 0 } ?: key.substringAfterLast(':', "").toIntOrNull() ?: 0
            val flags = obj.string("flags")
            TorrentPeer(
                address = address,
                port = port,
                clientName = obj.string("client"),
                progress = obj.double("progress"),
                rateToClient = obj.long("dl_speed"),
                rateToPeer = obj.long("up_speed"),
                flagStr = flags,
                isEncrypted = flags.contains('E'),
                isIncoming = flags.contains('I')
            )
        }.orEmpty()

        return TorrentDetail(
            id = hash,
            name = props.string("name"),
            status = mapped.status,
            percentDone = progress,
            totalSize = totalSize,
            leftUntilDone = (totalSize * (1.0 - progress)).toLong().coerceAtLeast(0),
            rateDownload = info?.long("dlspeed") ?: props.long("dl_speed"),
            rateUpload = info?.long("upspeed") ?: props.long("up_speed"),
            uploadRatio = info?.double("ratio") ?: props.double("share_ratio"),
            eta = (info?.long("eta") ?: props.long("eta")).takeIf { it in 0..8_640_000 } ?: -1,
            error = mapped.error,
            errorString = if (mapped.error > 0) state else null,
            downloadDir = props.string("save_path"),
            isFinished = progress >= 1.0,
            isStalled = mapped.stalled,
            peersConnected = info?.let { it.int("num_seeds") + it.int("num_leechs") } ?: props.int("nb_connections"),
            peersSendingToUs = info?.int("num_seeds") ?: props.int("seeds"),
            peersGettingFromUs = info?.int("num_leechs") ?: props.int("leechs"),
            labels = info?.string("tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() },
            addedDate = info?.long("added_on") ?: props.long("addition_date"),
            uploadedEver = props.long("total_uploaded"),
            downloadedEver = props.long("total_downloaded"),
            magnetLink = "magnet:?xt=urn:btih:$hash",
            files = files?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val size = obj.long("size")
                TorrentFile(
                    name = obj.string("name"),
                    length = size,
                    bytesCompleted = (size * obj.double("progress")).toLong(),
                    priority = obj.int("priority")
                )
            },
            trackerStats = trackerStats,
            peers = peerList,
            comment = props.string("comment").takeIf { it.isNotBlank() },
            creator = props.string("created_by").takeIf { it.isNotBlank() },
            dateCreated = props.long("creation_date"),
            doneDate = props.long("completion_date").takeIf { it > 0 } ?: 0,
            startDate = props.long("addition_date")
        )
    }

    override suspend fun start(server: DownloaderServer, ids: List<String>) {
        if (ids.isEmpty()) return
        request(server, "/torrents/resume", "POST", form("hashes" to hashParam(ids)))
    }

    override suspend fun stop(server: DownloaderServer, ids: List<String>) {
        if (ids.isEmpty()) return
        request(server, "/torrents/pause", "POST", form("hashes" to hashParam(ids)))
    }

    override suspend fun verify(server: DownloaderServer, ids: List<String>) {
        if (ids.isEmpty()) return
        request(server, "/torrents/recheck", "POST", form("hashes" to hashParam(ids)))
    }

    override suspend fun reannounce(server: DownloaderServer, ids: List<String>) {
        if (ids.isEmpty()) return
        request(server, "/torrents/reannounce", "POST", form("hashes" to hashParam(ids)))
    }

    override suspend fun remove(server: DownloaderServer, ids: List<String>, deleteData: Boolean) {
        if (ids.isEmpty()) return
        request(server, "/torrents/delete", "POST", form(
            "hashes" to hashParam(ids),
            "deleteFiles" to deleteData.toString()
        ))
    }

    override suspend fun addMagnet(server: DownloaderServer, magnet: String, downloadDir: String?, paused: Boolean) {
        val fields = mutableListOf("urls" to magnet.trim(), "paused" to paused.toString())
        if (!downloadDir.isNullOrBlank()) fields += "savepath" to downloadDir.trim()
        val text = request(server, "/torrents/add", "POST", form(*fields.toTypedArray()))
        if (text.trim().equals("Fails.", ignoreCase = true)) throw DownloaderException("qBittorrent 拒绝了该任务")
    }

    override suspend fun setAltSpeed(server: DownloaderServer, enabled: Boolean) {
        repeat(3) {
            val current = request(server, "/transfer/speedLimitsMode").trim() == "1"
            if (current == enabled) return
            request(server, "/transfer/toggleSpeedLimitsMode", "POST", form())
        }
        val confirmed = request(server, "/transfer/speedLimitsMode").trim() == "1"
        if (confirmed != enabled) throw DownloaderException("qBittorrent 限速模式切换未生效，请稍后重试")
    }

    override suspend fun setAltSpeedLimits(server: DownloaderServer, downKbps: Long, upKbps: Long) {
        val json = JsonObject().apply {
            addProperty("alt_dl_limit", downKbps.coerceAtLeast(0) * 1024)
            addProperty("alt_up_limit", upKbps.coerceAtLeast(0) * 1024)
        }
        request(server, "/app/setPreferences", "POST", form("json" to gson.toJson(json)))
    }

    override suspend fun setSpeedLimit(server: DownloaderServer, downKbps: Long, upKbps: Long, enabled: Boolean) {
        // 参数沿用接口命名（KiB/s），qBittorrent preferences 接收字节/秒。
        val down = if (enabled) downKbps.coerceAtLeast(0) * 1024 else 0
        val up = if (enabled) upKbps.coerceAtLeast(0) * 1024 else 0
        setPreferences(server, mapOf("dl_limit" to down, "up_limit" to up))
    }

    override suspend fun setSpeedLimits(
        server: DownloaderServer,
        altMode: Boolean,
        downKbps: Long,
        upKbps: Long,
        altDownKbps: Long,
        altUpKbps: Long
    ) {
        val down = downKbps.coerceAtLeast(0) * 1024
        val up = upKbps.coerceAtLeast(0) * 1024
        val altDown = altDownKbps.coerceAtLeast(0) * 1024
        val altUp = altUpKbps.coerceAtLeast(0) * 1024
        // 一次性写入四项，避免下载/上传分别请求造成半提交。
        setPreferences(server, mapOf(
            "dl_limit" to down,
            "up_limit" to up,
            "alt_dl_limit" to altDown,
            "alt_up_limit" to altUp
        ))
        try {
            setAltSpeed(server, altMode)
        } catch (failure: DownloaderException) {
            throw DownloaderException("限速数值已保存，但模式切换失败：${failure.message}")
        }
    }

    private suspend fun setPreferences(server: DownloaderServer, values: Map<String, Long>) {
        val json = JsonObject().apply { values.forEach { (key, value) -> addProperty(key, value) } }
        request(server, "/app/setPreferences", "POST", form("json" to gson.toJson(json)))
    }

    override suspend fun test(server: DownloaderServer, passwordOverride: String?): String {
        validate(server)
        val probe = server.copy(id = "__probe_qb_${System.nanoTime()}")
        try {
            val version = request(probe, "/app/version", passwordOverride = passwordOverride).trim()
            val webApi = request(probe, "/app/webapiVersion", passwordOverride = passwordOverride).trim()
            val suffix = if (webApi.isBlank()) "" else "（WebAPI $webApi）"
            return "已连接 qBittorrent ${version.ifBlank { "" }}$suffix".trim()
        } finally {
            sids.remove(probe.id)
        }
    }

    private val runningStates = setOf(
        "downloading", "forcedDL", "metaDL", "allocating", "stalledDL",
        "uploading", "forcedUP", "stalledUP"
    )
    private val pausedStates = setOf("pausedDL", "pausedUP", "stoppedDL", "stoppedUP")

    private data class MappedState(val status: Int, val stalled: Boolean, val error: Int)

    private fun mapState(state: String): MappedState = when (state) {
        "error", "missingFiles" -> MappedState(4, false, 1)
        "uploading", "forcedUP" -> MappedState(6, false, 0)
        "stalledUP" -> MappedState(6, true, 0)
        "queuedUP" -> MappedState(5, false, 0)
        "pausedUP", "stoppedUP", "pausedDL", "stoppedDL" -> MappedState(0, false, 0)
        "queuedDL" -> MappedState(3, false, 0)
        "checkingUP", "checkingDL", "checkingResumeData", "moving" -> MappedState(2, false, 0)
        "downloading", "forcedDL", "metaDL", "allocating" -> MappedState(4, false, 0)
        "stalledDL" -> MappedState(4, true, 0)
        else -> MappedState(0, false, 0)
    }

    private fun summary(obj: JsonObject): TorrentSummary {
        val state = obj.string("state")
        val mapped = mapState(state)
        val progress = obj.double("progress")
        val totalSize = obj.long("total_size").takeIf { it > 0 } ?: obj.long("size")
        val tags = obj.string("tags").split(',').map { it.trim() }.filter { it.isNotBlank() }
        return TorrentSummary(
            id = obj.string("hash"),
            name = obj.string("name"),
            status = mapped.status,
            percentDone = progress,
            totalSize = totalSize,
            leftUntilDone = (totalSize * (1.0 - progress)).toLong().coerceAtLeast(0),
            rateDownload = obj.long("dlspeed"),
            rateUpload = obj.long("upspeed"),
            uploadRatio = obj.double("ratio"),
            eta = obj.long("eta").takeIf { it in 0..8_640_000 } ?: -1,
            error = mapped.error,
            errorString = if (mapped.error > 0) state else null,
            downloadDir = obj.string("save_path"),
            isFinished = progress >= 1.0,
            isStalled = mapped.stalled,
            peersConnected = obj.int("num_seeds") + obj.int("num_leechs"),
            peersSendingToUs = obj.int("num_seeds"),
            peersGettingFromUs = obj.int("num_leechs"),
            labels = tags.takeIf { it.isNotEmpty() },
            addedDate = obj.long("added_on"),
            uploadedEver = obj.long("uploaded"),
            downloadedEver = obj.long("downloaded")
        )
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrDefault("") }.orEmpty()

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asLong }.getOrDefault(0L) } ?: 0L

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0

    private fun JsonObject.double(key: String): Double =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrDefault(0.0) } ?: 0.0

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false
}
