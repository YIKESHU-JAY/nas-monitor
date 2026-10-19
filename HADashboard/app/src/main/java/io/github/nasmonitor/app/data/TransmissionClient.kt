package io.github.nasmonitor.app.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

data class DownloaderServer(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: Int = 9091,
    val username: String = "",
    val useHttps: Boolean = false,
    val allowInsecure: Boolean = false,
    val rpcPath: String = "/transmission/rpc",
    val protocol: DownloaderProtocol = DownloaderProtocol.TRANSMISSION,
    /** 卡片排序序号，数值小的排在前面；相同序号按创建顺序。 */
    val order: Int = 0
)

typealias TransmissionSession = DownloaderSession
typealias TransmissionStats = DownloaderStats

data class TorrentSummary(
    val id: String = "",
    val name: String = "",
    val status: Int = 0,
    val percentDone: Double = 0.0,
    val totalSize: Long = 0,
    val leftUntilDone: Long = 0,
    val rateDownload: Long = 0,
    val rateUpload: Long = 0,
    val uploadRatio: Double = 0.0,
    val eta: Long = -1,
    val error: Int = 0,
    val errorString: String? = null,
    val downloadDir: String = "",
    val isFinished: Boolean = false,
    val isStalled: Boolean = false,
    val peersConnected: Int = 0,
    val peersSendingToUs: Int = 0,
    val peersGettingFromUs: Int = 0,
    val labels: List<String>? = null,
    val addedDate: Long = 0,
    val uploadedEver: Long = 0,
    val downloadedEver: Long = 0
) {
    val statusLabel: String get() = torrentStatusLabel(status, isStalled)

    val isRunning: Boolean get() = status == 4 || status == 6
}

data class TorrentFile(
    val name: String = "",
    val length: Long = 0,
    val bytesCompleted: Long = 0,
    val wanted: Boolean = true,
    val priority: Int = 0
)

data class TorrentTracker(
    val id: Int = 0,
    val announce: String = "",
    val scrape: String = "",
    val tier: Int = 0,
    val host: String = ""
)

data class TrackerStat(
    val id: Int = 0,
    val host: String = "",
    val announce: String = "",
    val lastAnnounceResult: String? = null,
    val lastAnnounceSucceeded: Boolean = false,
    val seederCount: Int = -1,
    val leecherCount: Int = -1,
    val downloadCount: Int = -1,
    val nextAnnounceTime: Long = 0
)

data class TorrentPeer(
    val address: String = "",
    val port: Int = 0,
    val clientName: String = "",
    val progress: Double = 0.0,
    val rateToClient: Long = 0,
    val rateToPeer: Long = 0,
    val flagStr: String = "",
    val isEncrypted: Boolean = false,
    val isIncoming: Boolean = false
)

data class TorrentDetail(
    val id: String = "",
    val name: String = "",
    val status: Int = 0,
    val percentDone: Double = 0.0,
    val totalSize: Long = 0,
    val leftUntilDone: Long = 0,
    val rateDownload: Long = 0,
    val rateUpload: Long = 0,
    val uploadRatio: Double = 0.0,
    val eta: Long = -1,
    val error: Int = 0,
    val errorString: String? = null,
    val downloadDir: String = "",
    val isFinished: Boolean = false,
    val isStalled: Boolean = false,
    val peersConnected: Int = 0,
    val peersSendingToUs: Int = 0,
    val peersGettingFromUs: Int = 0,
    val labels: List<String>? = null,
    val addedDate: Long = 0,
    val uploadedEver: Long = 0,
    val downloadedEver: Long = 0,
    val files: List<TorrentFile>? = null,
    val trackers: List<TorrentTracker>? = null,
    val trackerStats: List<TrackerStat>? = null,
    val peers: List<TorrentPeer>? = null,
    val pieces: String? = null,
    val pieceCount: Int = 0,
    val pieceSize: Long = 0,
    val magnetLink: String? = null,
    val comment: String? = null,
    val creator: String? = null,
    val dateCreated: Long = 0,
    val activityDate: Long = 0,
    val doneDate: Long = 0,
    val startDate: Long = 0,
    val bandwidthPriority: Int = 0
) {
    val statusLabel: String get() = torrentStatusLabel(status, isStalled)

    val isRunning: Boolean get() = status == 4 || status == 6
}

open class DownloaderException(message: String, cause: Throwable? = null) : IOException(message, cause)

class TransmissionException(message: String, cause: Throwable? = null) : DownloaderException(message, cause)

internal fun torrentStatusLabel(status: Int, isStalled: Boolean): String = when (status) {
    0 -> "已暂停"
    1 -> "等待校验"
    2 -> "校验中"
    3 -> "排队下载"
    5 -> "排队做种"
    4 -> if (isStalled) "下载中 · 停滞" else "下载中"
    6 -> if (isStalled) "做种中 · 无连接" else "做种中"
    else -> "未知"
}

object TransmissionClient : DownloaderApi {
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val sessions = ConcurrentHashMap<String, String>()
    private val clients = ConcurrentHashMap<Boolean, OkHttpClient>()

    private val summaryFields = listOf(
        "id", "name", "status", "percentDone", "totalSize", "leftUntilDone",
        "rateDownload", "rateUpload", "uploadRatio", "eta", "error", "errorString",
        "downloadDir", "isFinished", "isStalled", "peersConnected", "peersSendingToUs",
        "peersGettingFromUs", "labels", "addedDate", "uploadedEver", "downloadedEver"
    )

    private val detailFields = summaryFields + listOf(
        "files", "fileStats", "trackerStats", "peers",
        "magnetLink", "comment", "creator", "dateCreated",
        "activityDate", "doneDate", "startDate", "bandwidthPriority"
    )

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
        val path = server.rpcPath.trim().let { if (it.isEmpty()) "/transmission/rpc" else if (it.startsWith("/")) it else "/$it" }
        return "$scheme://$hostPart$path"
    }

    /** UI 展示用：与 endpoint 的端口推导保持一致。 */
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

    private suspend fun rpc(
        server: DownloaderServer,
        method: String,
        arguments: JsonObject = JsonObject(),
        passwordOverride: String? = null
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val url = endpoint(server)
            val payload = JsonObject().apply {
                addProperty("method", method)
                add("arguments", arguments)
            }.toString()
            val client = client(server.allowInsecure)
            var attempt = 0
            while (attempt < 2) {
                val builder = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(jsonType))
                    .header("Accept", "application/json")
                if (server.username.isNotBlank()) {
                    builder.header("Authorization", Credentials.basic(server.username, passwordOverride ?: passwordOf(server)))
                }
                sessions[server.id]?.let { builder.header("X-Transmission-Session-Id", it) }
                val response = try {
                    call(client, builder.build())
                } catch (error: IOException) {
                    throw TransmissionException("无法连接 ${displayAddress(server)}：${error.message ?: "网络错误"}", error)
                }
                response.use { resp ->
                    val sessionId = resp.header("X-Transmission-Session-Id")
                    if (!sessionId.isNullOrBlank()) sessions[server.id] = sessionId
                    if (resp.code == 409 && attempt == 0) {
                        if (sessionId.isNullOrBlank()) {
                            throw TransmissionException("服务器未返回 RPC Session-Id，请检查是否为 Transmission RPC 端口")
                        }
                        attempt++
                        return@use
                    }
                    if (resp.code == 401) throw TransmissionException("用户名或密码错误")
                    if (resp.code == 403) throw TransmissionException("RPC 拒绝访问，请检查白名单或认证设置")
                    if (resp.code == 404) throw TransmissionException("RPC 路径错误，请检查路径设置")
                    if (!resp.isSuccessful) throw TransmissionException("服务器返回 HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    val parsed = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                    val root = parsed?.takeIf { it.isJsonObject }
                        ?: throw TransmissionException("服务器返回内容不是有效 JSON 对象")
                    val result = root.get("result")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (result != "success") throw TransmissionException("Transmission 返回错误：${result.ifBlank { "unknown" }}")
                    return@withContext root.getAsJsonObject("arguments") ?: JsonObject()
                }
            }
            throw TransmissionException("会话握手失败，请重试")
        }

    private fun passwordOf(server: DownloaderServer): String = DownloaderAuth.password(server)

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

    override suspend fun session(server: DownloaderServer, passwordOverride: String?): TransmissionSession {
        val args = rpc(server, "session-get", passwordOverride = passwordOverride)
        return TransmissionSession(
            version = args.string("version"),
            rpcVersion = args.int("rpc-version"),
            downloadDir = args.string("download-dir"),
            altSpeedEnabled = args.bool("alt-speed-enabled"),
            altSpeedDown = args.long("alt-speed-down") * 1024,
            altSpeedUp = args.long("alt-speed-up") * 1024,
            speedLimitDown = args.long("speed-limit-down") * 1024,
            speedLimitUp = args.long("speed-limit-up") * 1024,
            speedLimitDownEnabled = args.bool("speed-limit-down-enabled"),
            speedLimitUpEnabled = args.bool("speed-limit-up-enabled")
        )
    }

    override suspend fun stats(server: DownloaderServer): TransmissionStats {
        val args = rpc(server, "session-stats")
        val cumulative = args.get("cumulative-stats")?.takeIf { it.isJsonObject }?.asJsonObject
        val torrents = runCatching {
            val fieldArgs = JsonObject().apply { add("fields", gson.toJsonTree(listOf("error"))) }
            rpc(server, "torrent-get", fieldArgs).getAsJsonArray("torrents")
        }.getOrNull()
        // free-space 需要下载目录；session-stats 不带目录，失败时保持 -1，不影响主统计。
        val freeSpace = runCatching {
            val sessionArgs = rpc(server, "session-get")
            val dir = sessionArgs.string("download-dir")
            if (dir.isBlank()) return@runCatching -1L
            val args = JsonObject().apply { addProperty("path", dir) }
            rpc(server, "free-space", args).long("size-bytes").takeIf { it > 0 } ?: -1L
        }.getOrDefault(-1L)
        val errorCount = torrents?.count { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.int("error")?.let { it > 0 } == true
        } ?: 0
        return TransmissionStats(
            downloadSpeed = args.long("downloadSpeed"),
            uploadSpeed = args.long("uploadSpeed"),
            activeTorrentCount = args.int("activeTorrentCount"),
            pausedTorrentCount = args.int("pausedTorrentCount"),
            torrentCount = args.int("torrentCount"),
            totalDownloaded = cumulative?.long("downloadedBytes") ?: -1,
            totalUploaded = cumulative?.long("uploadedBytes") ?: -1,
            // session-stats 不提供磁盘剩余空间；仅在 session-get 时读取，避免每轮多一次 RPC。
            freeSpace = freeSpace,
            errorTorrentCount = errorCount,
            queuedIoCount = -1
        )
    }

    override suspend fun torrents(server: DownloaderServer): List<TorrentSummary> {
        val args = JsonObject().apply { add("fields", gson.toJsonTree(summaryFields)) }
        val result = rpc(server, "torrent-get", args)
        val array = result.getAsJsonArray("torrents") ?: return emptyList()
        return array.map { element ->
            try {
                gson.fromJson(element, TorrentSummary::class.java)
            } catch (failure: Exception) {
                throw TransmissionException("任务列表解析失败：${failure.message ?: "字段格式不兼容"}")
            }
        }
    }

    override suspend fun torrent(server: DownloaderServer, id: String): TorrentDetail? {
        val numericId = id.toIntOrNull() ?: throw TransmissionException("任务 ID 无效：$id")
        val args = JsonObject().apply {
            add("fields", gson.toJsonTree(detailFields))
            add("ids", JsonArray().apply { add(numericId) })
        }
        val result = rpc(server, "torrent-get", args)
        val array = result.getAsJsonArray("torrents") ?: return null
        val element = if (array.size() > 0) array.get(0) else return null
        val detail = try {
            gson.fromJson(element, TorrentDetail::class.java)
        } catch (failure: Exception) {
            throw TransmissionException("任务详情解析失败：${failure.message ?: "字段格式不兼容"}")
        }
        val stats = element.asJsonObject.getAsJsonArray("fileStats")
        val files = detail.files
        if (files == null || stats == null) return detail
        return detail.copy(files = files.mapIndexed { index, file ->
            val stat = if (index < stats.size()) stats.get(index).takeIf { it.isJsonObject }?.asJsonObject else null
            file.copy(
                bytesCompleted = stat?.get("bytesCompleted")?.takeIf { !it.isJsonNull }?.asLong ?: file.bytesCompleted,
                wanted = stat?.get("wanted")?.takeIf { !it.isJsonNull }?.asBoolean ?: file.wanted,
                priority = stat?.get("priority")?.takeIf { !it.isJsonNull }?.asInt ?: file.priority
            )
        })
    }

    private suspend fun action(server: DownloaderServer, method: String, ids: List<String>, extra: JsonObject = JsonObject()) {
        if (ids.isEmpty()) return
        val args = JsonObject().apply {
            add("ids", JsonArray().apply {
                ids.forEach { raw ->
                    add(raw.toIntOrNull() ?: throw TransmissionException("任务 ID 无效：$raw"))
                }
            })
            extra.entrySet().forEach { (key, value) -> add(key, value) }
        }
        rpc(server, method, args)
    }

    override suspend fun start(server: DownloaderServer, ids: List<String>) = action(server, "torrent-start", ids)
    override suspend fun stop(server: DownloaderServer, ids: List<String>) = action(server, "torrent-stop", ids)
    override suspend fun verify(server: DownloaderServer, ids: List<String>) = action(server, "torrent-verify", ids)
    override suspend fun reannounce(server: DownloaderServer, ids: List<String>) = action(server, "torrent-reannounce", ids)

    override suspend fun remove(server: DownloaderServer, ids: List<String>, deleteData: Boolean) {
        val extra = JsonObject().apply { addProperty("delete-local-data", deleteData) }
        action(server, "torrent-remove", ids, extra)
    }

    override suspend fun addMagnet(server: DownloaderServer, magnet: String, downloadDir: String?, paused: Boolean) {
        val args = JsonObject().apply {
            addProperty("filename", magnet.trim())
            addProperty("paused", paused)
            if (!downloadDir.isNullOrBlank()) addProperty("download-dir", downloadDir.trim())
        }
        rpc(server, "torrent-add", args)
    }

    override suspend fun setAltSpeed(server: DownloaderServer, enabled: Boolean) {
        val args = JsonObject().apply { addProperty("alt-speed-enabled", enabled) }
        rpc(server, "session-set", args)
    }

    override suspend fun setAltSpeedLimits(server: DownloaderServer, downKbps: Long, upKbps: Long) {
        val args = JsonObject().apply {
            addProperty("alt-speed-down", downKbps.coerceAtLeast(0))
            addProperty("alt-speed-up", upKbps.coerceAtLeast(0))
        }
        rpc(server, "session-set", args)
    }

    override suspend fun setSpeedLimit(server: DownloaderServer, downKbps: Long, upKbps: Long, enabled: Boolean) {
        val args = JsonObject().apply {
            addProperty("speed-limit-down", downKbps.coerceAtLeast(0))
            addProperty("speed-limit-up", upKbps.coerceAtLeast(0))
            addProperty("speed-limit-down-enabled", enabled && downKbps > 0)
            addProperty("speed-limit-up-enabled", enabled && upKbps > 0)
        }
        rpc(server, "session-set", args)
    }

    override suspend fun setSpeedLimits(
        server: DownloaderServer,
        altMode: Boolean,
        downKbps: Long,
        upKbps: Long,
        altDownKbps: Long,
        altUpKbps: Long
    ) {
        val down = downKbps.coerceAtLeast(0)
        val up = upKbps.coerceAtLeast(0)
        val altDown = altDownKbps.coerceAtLeast(0)
        val altUp = altUpKbps.coerceAtLeast(0)
        val args = JsonObject().apply {
            addProperty("speed-limit-down", down)
            addProperty("speed-limit-up", up)
            addProperty("speed-limit-down-enabled", down > 0)
            addProperty("speed-limit-up-enabled", up > 0)
            addProperty("alt-speed-down", altDown)
            addProperty("alt-speed-up", altUp)
            addProperty("alt-speed-enabled", altMode)
        }
        rpc(server, "session-set", args)
    }

    override suspend fun test(server: DownloaderServer, passwordOverride: String?): String {
        validate(server)
        val probe = server.copy(id = "__probe__${System.nanoTime()}")
        try {
            val session = session(probe, passwordOverride)
            return "已连接 Transmission ${session.version.ifBlank { "" }}（RPC ${session.rpcVersion}）".trim()
        } finally {
            sessions.remove(probe.id)
        }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asLong }.getOrDefault(0L) } ?: 0L

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false


    internal fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (value >= 100) "%.0f %s".format(value, units[index])
        else if (value >= 10) "%.1f %s".format(value, units[index])
        else "%.2f %s".format(value, units[index])
    }

    internal fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

    internal fun formatEta(seconds: Long): String {
        if (seconds < 0) return "—"
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m"
        return "${hours / 24}d ${hours % 24}h"
    }

    internal fun formatRatio(ratio: Double): String = when {
        ratio.isNaN() || ratio < 0 -> "—"
        ratio >= 100 -> "%.0f".format(ratio)
        else -> "%.2f".format(ratio)
    }
}
