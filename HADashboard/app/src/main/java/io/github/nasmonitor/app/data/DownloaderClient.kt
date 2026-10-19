package io.github.nasmonitor.app.data

/** 下载器协议类型。新增协议时同步更新显示名、默认端口与默认路径。 */
enum class DownloaderProtocol(val label: String, val defaultPort: Int, val defaultPath: String) {
    TRANSMISSION("Transmission", 9091, "/transmission/rpc"),
    QBITTORRENT("qBittorrent", 8080, "/api/v2");

    companion object {
        fun fromStorage(value: String?): DownloaderProtocol =
            entries.firstOrNull { it.name == value } ?: TRANSMISSION
    }
}

/** 下载器会话信息。qBittorrent 无 RPC 版本，rpcVersion 固定为 0。 */
data class DownloaderSession(
    val version: String = "",
    val rpcVersion: Int = 0,
    val downloadDir: String = "",
    val altSpeedEnabled: Boolean = false,
    val altSpeedDown: Long = 0,
    val altSpeedUp: Long = 0,
    val speedLimitDown: Long = 0,
    val speedLimitUp: Long = 0,
    val speedLimitDownEnabled: Boolean = false,
    val speedLimitUpEnabled: Boolean = false
)

data class DownloaderStats(
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
    val activeTorrentCount: Int = 0,
    val pausedTorrentCount: Int = 0,
    val torrentCount: Int = 0,
    /** 历史累计下载量；协议不提供时为 -1，UI 显示“—”。 */
    val totalDownloaded: Long = -1,
    /** 历史累计上传量；协议不提供时为 -1。 */
    val totalUploaded: Long = -1,
    /** 下载目录剩余空间；协议不提供时为 -1。 */
    val freeSpace: Long = -1,
    /** 出错任务数。 */
    val errorTorrentCount: Int = 0,
    /** 等待 I/O 的队列任务数；协议不提供时为 -1。 */
    val queuedIoCount: Int = -1
)

/**
 * 统一下载器操作接口。任务 ID 使用字符串：Transmission 为数字 id 的字符串形式，
 * qBittorrent 为 info hash。
 */
interface DownloaderApi {
    fun validate(server: DownloaderServer)
    fun endpoint(server: DownloaderServer): String
    fun displayAddress(server: DownloaderServer): String
    suspend fun session(server: DownloaderServer, passwordOverride: String? = null): DownloaderSession
    suspend fun stats(server: DownloaderServer): DownloaderStats
    suspend fun torrents(server: DownloaderServer): List<TorrentSummary>
    suspend fun torrent(server: DownloaderServer, id: String): TorrentDetail?
    suspend fun start(server: DownloaderServer, ids: List<String>)
    suspend fun stop(server: DownloaderServer, ids: List<String>)
    suspend fun verify(server: DownloaderServer, ids: List<String>)
    suspend fun reannounce(server: DownloaderServer, ids: List<String>)
    suspend fun remove(server: DownloaderServer, ids: List<String>, deleteData: Boolean)
    suspend fun addMagnet(server: DownloaderServer, magnet: String, downloadDir: String?, paused: Boolean)
    suspend fun setAltSpeed(server: DownloaderServer, enabled: Boolean)
    /** 保存备用限速的上下行值；不支持单独设置时抛出 [DownloaderException]。 */
    suspend fun setAltSpeedLimits(server: DownloaderServer, downKbps: Long, upKbps: Long)
    suspend fun setSpeedLimit(server: DownloaderServer, downKbps: Long, upKbps: Long, enabled: Boolean)
    /**
     * 一次保存常规/备用限速与当前模式。[downKbps]/[upKbps] 为常规限速，
     * [altDownKbps]/[altUpKbps] 为备用限速，单位均为 KiB/s，0 表示不限速。
     */
    suspend fun setSpeedLimits(
        server: DownloaderServer,
        altMode: Boolean,
        downKbps: Long,
        upKbps: Long,
        altDownKbps: Long,
        altUpKbps: Long
    )
    suspend fun test(server: DownloaderServer, passwordOverride: String? = null): String
}

/** 密码由调用方注入，避免数据层直接依赖 Android Context。 */
object DownloaderAuth {
    @Volatile
    var passwordProvider: ((DownloaderServer) -> String)? = null

    fun password(server: DownloaderServer): String = passwordProvider?.invoke(server).orEmpty()
}

/** 按协议分发；密码注入由 [DownloaderStore] 在初始化时完成。 */
object DownloaderApis {
    private val transmission = TransmissionClient
    private val qbittorrent = QbittorrentApi

    fun of(server: DownloaderServer): DownloaderApi = when (server.protocol) {
        DownloaderProtocol.TRANSMISSION -> transmission
        DownloaderProtocol.QBITTORRENT -> qbittorrent
    }

    fun of(protocol: DownloaderProtocol): DownloaderApi = when (protocol) {
        DownloaderProtocol.TRANSMISSION -> transmission
        DownloaderProtocol.QBITTORRENT -> qbittorrent
    }

    val protocols: List<DownloaderProtocol> get() = DownloaderProtocol.entries
}
