package io.github.nasmonitor.app

import com.google.gson.annotations.SerializedName

// ── Overview ──
data class LiveHostEntry(
    val ip: String = "",
    val count: Int = 0,
    val errors: Int = 0,
    val last_seen: String = "",
    val geo: String = ""
)

data class LiveSecurityData(
    val hosts: List<String> = emptyList(),
    val rows: List<LiveHostEntry> = emptyList(),
    val total: Int = 0,
    val unique: Int = 0
)

data class OverviewResponse(
    val servers: List<ManagedServer>? = null,
    val nas: ServerStatus = ServerStatus(),
    val aliyun: ServerStatus = ServerStatus(),
    val tencent: ServerStatus = ServerStatus(),
    val latency: LatencyData = LatencyData(),
    val timestamp: String = "",
    val alerts: List<AlertItem> = emptyList(),
    val router: RouterStatus? = null
)

data class AlertItem(
    val host: String = "",
    val level: String = "warning",
    val kind: String = "",
    val message: String = "",
    @SerializedName("first_seen") val firstSeen: String? = null
)

data class ServerStatus(
    val status: String = "unknown",
    val cpu: Double = 0.0,
    val memory: Double = 0.0,
    val disks: List<DiskInfo> = emptyList(),
    val load: LoadInfo? = null,
    val uptime: String = "",
    val network: NetworkSummary? = null,
    @SerializedName("core_services") val coreServices: Map<String, ServiceStatus> = emptyMap(),
    @SerializedName("mesh_ip") val meshIp: String? = null,
    @SerializedName("public_ip") val publicIp: String? = null
)

data class NetworkSummary(
    @SerializedName("interface") val interfaceName: String = "",
    @SerializedName("rx_bps") val rxBps: Double = 0.0,
    @SerializedName("tx_bps") val txBps: Double = 0.0
)

data class DiskInfo(
    val mount: String = "",
    val device: String = "",
    val percent: Double = 0.0,
    @SerializedName("total_gb") val totalGb: Double = 0.0,
    @SerializedName("used_gb") val usedGb: Double = 0.0
)

data class LoadInfo(
    @SerializedName("min1") val min1: Double = 0.0,
    @SerializedName("min5") val min5: Double = 0.0,
    @SerializedName("min15") val min15: Double = 0.0
)

data class LatencyData(
    val aliyun: Double? = null,
    val nas: Double? = null,
    @SerializedName("nas_to_aliyun") val nasToAliyun: Double? = null
)


// ── Airport traffic ──
data class AirportTrafficResponse(
    val items: List<AirportTrafficItem> = emptyList(),
    val timestamp: String = "",
    val source: String = ""
)

data class AirportTrafficItem(
    val id: String = "",
    val name: String = "",
    val connections: Int = 0,
    @SerializedName("remaining_gb") val remainingGb: Double = 0.0,
    @SerializedName("today_used_gb") val todayUsedGb: Double? = null,
    @SerializedName("today_usage_partial") val todayUsagePartial: Boolean = true,
    @SerializedName("total_gb") val totalGb: Double = 0.0,
    @SerializedName("used_percent") val usedPercent: Double = 0.0,
    @SerializedName("reset_days") val resetDays: Int? = null,
    val expire: String? = null,
    val level: String = "normal"
)

// ── Mihomo proxy instances ──
data class MihomoOverviewResponse(
    val instances: List<MihomoInstance> = emptyList()
)

data class MihomoInstance(
    val id: String = "",
    val name: String = "",
    val available: Boolean = true,
    val error: String? = null,
    val version: String = "",
    val meta: Boolean = false,
    val mode: String = "",
    @SerializedName("mixed_port") val mixedPort: Int? = null,
    val connections: Int = 0,
    @SerializedName("upload_total") val uploadTotal: Long = 0,
    @SerializedName("download_total") val downloadTotal: Long = 0,
    @SerializedName("upload_speed") val uploadSpeed: Long = 0,
    @SerializedName("download_speed") val downloadSpeed: Long = 0,
    val groups: List<MihomoGroup> = emptyList(),
    val panel: String = ""
)

data class MihomoGroup(
    val name: String = "",
    val type: String = "",
    val now: String? = null,
    val all: List<String> = emptyList(),
    val hidden: Boolean = false
)

data class MihomoAirportsResponse(
    val instances: List<MihomoAirportInstance> = emptyList()
)

data class MihomoAirportInstance(
    val id: String = "",
    val name: String = "",
    val items: List<AirportTrafficItem> = emptyList(),
    val history: List<MihomoTrafficPoint> = emptyList(),
    @SerializedName("history_seconds") val historySeconds: Int = 5,
    val timestamp: Long = 0,
    val error: String? = null
)

data class MihomoTrafficPoint(
    val ts: Long = 0,
    val up: Long = 0,
    val down: Long = 0,
    val connections: Int = 0
)

data class MihomoGroupSelectResponse(
    val group: String = "",
    val now: String? = null,
    val applied: Boolean = false
)

data class MihomoDelayResponse(
    val name: String = "",
    val delay: Int? = null
)

data class MihomoPanelUrl(
    val url: String = ""
)

data class MihomoConnection(
    val id: String = "",
    val host: String = "",
    val destination: String = "",
    val port: String = "",
    val network: String = "",
    val process: String = "",
    val rule: String = "",
    val chains: List<String> = emptyList(),
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = ""
)

data class MihomoConnectionsResponse(
    val connections: List<MihomoConnection> = emptyList(),
    @SerializedName("upload_total") val uploadTotal: Long = 0,
    @SerializedName("download_total") val downloadTotal: Long = 0
)

data class MihomoRule(
    val index: Int = 0,
    val type: String = "",
    val payload: String = "",
    val proxy: String = "",
    val size: Int? = null,
    val disabled: Boolean = false
)

data class MihomoRulesResponse(
    val rules: List<MihomoRule> = emptyList()
)

data class MihomoConfigs(
    val mode: String = "",
    @SerializedName("mixed-port") val mixedPort: Int? = null,
    @SerializedName("socks-port") val socksPort: Int? = null,
    @SerializedName("allow-lan") val allowLan: Boolean? = null,
    @SerializedName("log-level") val logLevel: String? = null,
    val ipv6: Boolean? = null
)

data class MihomoProvider(
    val name: String = "",
    @SerializedName("vehicle_type") val vehicleType: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("node_count") val nodeCount: Int = 0,
    @SerializedName("has_subscription") val hasSubscription: Boolean = false
)

data class MihomoProvidersResponse(
    val providers: List<MihomoProvider> = emptyList()
)

// ── NAS Detail ──
data class NasDetailResponse(
    val system: SystemData? = null,
    val docker: DockerData? = null,
    val services: Map<String, ServiceStatus> = emptyMap(),
    val ha: HaData? = null,
    @SerializedName("disk_temp") val diskTemp: Int = 0,
    @SerializedName("hdd_temps") val hddTemps: Map<String, Int> = emptyMap(),
    val kuma: List<KumaMonitor> = emptyList(),
    val latency: LatencyData? = null
)

data class HaData(
    val volumes: List<HaVolume> = emptyList(),
    @SerializedName("disk_details") val diskDetails: Map<String, DiskDetail> = emptyMap(),
    val fans: Map<String, FanData> = emptyMap(),
    val ups: UpsData? = null,
    val cabinet: CabinetData? = null,
    val aliyun: Map<String, String> = emptyMap(),
    val kuma: List<KumaMonitor> = emptyList(),
    @SerializedName("cabinet_energy") val cabinetEnergy: String? = null,
    val error: String? = null
)

data class HaVolume(
    val name: String = "",
    val percent: Double = 0.0,
    val capacity: String = "",
    val used: String = "",
    val friendly: String = "",
    val device: String = ""
)

data class DiskDetail(
    val model: String = "",
    val rotational: Boolean = false,
    val temperature: Double? = null
)

data class FanData(
    val pwm: String? = null,
    val mode: String? = null,
    val rpm: String? = null
)

data class UpsData(
    val status: String? = null,
    val charge: String? = null,
    val load: String? = null,
    @SerializedName("battery_voltage") val batteryVoltage: String? = null,
    @SerializedName("input_voltage") val inputVoltage: String? = null,
    @SerializedName("output_voltage") val outputVoltage: String? = null
)

data class CabinetData(
    val temperature: String? = null,
    val humidity: String? = null,
    val power: String? = null,
    @SerializedName("energy_today") val energyToday: String? = null,
    @SerializedName("energy_month") val energyMonth: String? = null,
    @SerializedName("energy_total") val energyTotal: String? = null
)

data class SystemData(
    val cpu: CpuData? = null,
    val memory: MemoryData? = null,
    val disks: List<DiskInfo>? = null,
    val load: LoadInfo? = null,
    val network: List<NetworkInfo>? = null,
    val uptime: String? = null
)

data class CpuData(
    val percent: Double = 0.0,
    val user: Double = 0.0,
    val iowait: Double = 0.0,
    val cores: Int = 0
)

data class MemoryData(
    val percent: Double = 0.0,
    @SerializedName("total_gb") val totalGb: Double = 0.0,
    @SerializedName("used_gb") val usedGb: Double = 0.0,
    @SerializedName("available_gb") val availableGb: Double = 0.0
)

data class NetworkInfo(
    val name: String = "",
    @SerializedName("rx_mb") val rxMb: Double = 0.0,
    @SerializedName("tx_mb") val txMb: Double = 0.0
)

data class DockerData(
    val total: Int = 0,
    val running: Int = 0,
    val stopped: Int = 0,
    val healthy: Int = 0,
    val error: String? = null,
    val containers: List<ContainerInfo> = emptyList()
)

data class ContainerInfo(
    val name: String = "",
    val image: String = "",
    val status: String = "",
    val ports: String = "",
    val healthy: Boolean = false
)

data class ServiceStatus(
    val status: String = "down",
    val code: Int? = null,
    val ms: Double? = null
)

data class KumaMonitor(
    val name: String = "",
    val status: String = "down",
    val uptime: Double = 0.0,
    @SerializedName("latency_ms") val latencyMs: Double = 0.0
)

// ── Aliyun Detail ──
data class AliyunDetailResponse(
    val system: SystemData? = null,
    val services: Map<String, ServiceStatus> = emptyMap(),
    val latency: LatencyData? = null
)

data class TrendPoint(
    val t: Double = 0.0,
    val cpu: Float = 0f,
    val mem: Float = 0f,
    val disk: Float = 0f,
    val rx: Float = 0f,
    val tx: Float = 0f
)

data class TrendResponse(
    val host: String = "",
    val minutes: Int = 60,
    val points: List<TrendPoint> = emptyList()
)

data class TemperatureTrendPoint(
    val t: String = "",
    val temp: Float = 0f
)

data class TemperatureTrendResponse(
    val host: String = "",
    val minutes: Int = 60,
    val disks: Map<String, List<TemperatureTrendPoint>> = emptyMap()
)

data class ContainerStats(
    val name: String = "",
    val status: String = "",
    val image: String = "",
    @SerializedName("cpu_percent") val cpuPercent: Float = 0f,
    @SerializedName("memory_usage_mb") val memoryUsageMb: Float = 0f,
    @SerializedName("memory_limit_mb") val memoryLimitMb: Float = 0f,
    @SerializedName("memory_percent") val memoryPercent: Float = 0f,
    @SerializedName("network_rx_bytes") val networkRxBytes: Long = 0L,
    @SerializedName("network_tx_bytes") val networkTxBytes: Long = 0L,
    val uptime: String = ""
)

data class ContainerStatsResponse(
    val host: String = "",
    val containers: List<ContainerStats> = emptyList(),
    val error: String? = null
)

// ── 路由器 ──
data class RouterStatus(
    val status: String = "",
    val address: String = "",
    val internet: String = "unknown",
    val wan_speed: Int = 0,
    val wan_ip: String = "",
    val wan_name: String = "wan1",
    val wan_gateway: String = "",
    val wans: List<WanInfo> = emptyList(),
    val cpu_usage: Double = 0.0,
    val cpu_temp: Int = 0,
    val mem_usage: Double = 0.0,
    val mem_total: Double = 0.0,
    val mem_available: Double = 0.0,
    val uptime: Long = 0,
    val upload_speed: Double = 0.0,
    val download_speed: Double = 0.0,
    val connections: Int = 0,
    val online_user_count: Int = 0,
    val online_devices: Int = 0,
    val total_devices: Int = 0,
    val version: String = "",
    val speedtest_download: Double = 0.0,
    val speedtest_upload: Double = 0.0,
    val speedtest_status: String = "未测速",
    val speedtest_running: Boolean = false,
    val devices: List<RouterDevice> = emptyList(),
    val error: String? = null
)

data class WanInfo(
    val name: String = "",
    val ip: String = "",
    val speed: Int = 0,
    val internet: String = "unknown",
    val gateway: String = "",
    val upload_speed: Double = 0.0,
    val download_speed: Double = 0.0
)

data class RouterDevice(
    val name: String = "",
    val ip: String = ""
)


data class SpeedtestResult(
    val download: Double = 0.0,
    val upload: Double = 0.0,
    val unit: String = "Mbps",
    @com.google.gson.annotations.SerializedName("interface") val interfaceName: String = "",
    val timestamp: String? = null,
    val error: String? = null
)

data class SpeedtestRecord(
    val id: String = "",
    @com.google.gson.annotations.SerializedName("interface") val interfaceName: String = "",
    val timestamp: String = "",
    @com.google.gson.annotations.SerializedName("download_mbps") val downloadMbps: Double = 0.0,
    @com.google.gson.annotations.SerializedName("upload_mbps") val uploadMbps: Double = 0.0,
    @com.google.gson.annotations.SerializedName("ping_ms") val pingMs: Double = 0.0,
    @com.google.gson.annotations.SerializedName("jitter_ms") val jitterMs: Double = 0.0,
    val server: String = "",
    @com.google.gson.annotations.SerializedName("server_location") val serverLocation: String = "",
    @com.google.gson.annotations.SerializedName("source_ip") val sourceIp: String = "",
    val status: String = "",
    val error: String? = null
)

data class SpeedtestHistoryResponse(
    val items: List<SpeedtestRecord> = emptyList(),
    val total: Int = 0
)




data class NewApiChannel(val id:Int=0,val name:String="",val status:Int=0,val models:String="",val group:String="",val priority:Int=0,val weight:Int=0,@com.google.gson.annotations.SerializedName("response_time") val responseTime:Long=0,@com.google.gson.annotations.SerializedName("auto_ban") val autoBan:Int=0)
data class NewApiModelMeta(val id: Int = 0, @SerializedName("model_name") val modelName: String = "", val status: Int = 0, @SerializedName("bound_channels") val boundChannels: List<NewApiBoundChannel> = emptyList(), @SerializedName("enable_groups") val enableGroups: List<String> = emptyList())
data class NewApiBoundChannel(val name: String = "", val type: Int = 0)
data class NewApiModelPerformance(val model:String="",val requests:Int=0,val success:Int=0,val failed:Int=0,@com.google.gson.annotations.SerializedName("client_cancelled") val clientCancelled:Int=0,val unknown:Int=0,@com.google.gson.annotations.SerializedName("success_rate") val successRate:Double=0.0,@com.google.gson.annotations.SerializedName("avg_use_time") val avgUseTime:Double=0.0,@com.google.gson.annotations.SerializedName("avg_frt") val avgFrt:Double?=null)
data class NewApiGatewayOverview(val channels:List<NewApiChannel> = emptyList(),val models:List<NewApiModelPerformance> = emptyList(),@com.google.gson.annotations.SerializedName("requests_24h") val requests24h:Int?=null,@com.google.gson.annotations.SerializedName("recent_requests") val recentRequests:Int=0,@com.google.gson.annotations.SerializedName("disabled_channels") val disabledChannels:Int=0,@com.google.gson.annotations.SerializedName("auto_banned_channels") val autoBannedChannels:Int=0,@com.google.gson.annotations.SerializedName("rpm") val rpm:Int?=null)
data class NewApiPerformanceResponse(val models:List<NewApiModelPerformance> = emptyList(),val window:String="24h",@com.google.gson.annotations.SerializedName("generated_at") val generatedAt:String?=null)
data class NewApiUsageModel(
    val model: String = "",
    val requests: Long = 0,
    val tokens: Long = 0,
    val errors: Long = 0,
    @SerializedName("avg_use_time") val avgUseTime: Double = 0.0
)
data class NewApiUsageWindow(
    val label: String = "",
    val days: Int = 0,
    val start: Long = 0,
    val end: Long = 0,
    @SerializedName("start_date") val startDate: String = "",
    @SerializedName("end_date") val endDate: String = "",
    val models: List<NewApiUsageModel> = emptyList()
)
data class NewApiUsageDaily(
    val date: String = "",
    val model: String = "",
    val requests: Long = 0,
    val tokens: Long = 0
)
data class NewApiUsageCoverage(
    val complete: Boolean = false,
    val rows: Long = 0,
    @SerializedName("scan_limit") val scanLimit: Long = 0,
    val start: Long = 0,
    val end: Long = 0,
    val timezone: String = "",
    val error: String? = null
)
data class NewApiModelUsage(
    val windows: Map<String, NewApiUsageWindow> = emptyMap(),
    val daily: List<NewApiUsageDaily> = emptyList(),
    @SerializedName("generated_at") val generatedAt: String = "",
    val source: String = "",
    val stale: Boolean = false,
    val coverage: NewApiUsageCoverage = NewApiUsageCoverage()
)
data class NewApiAffinityCache(val enabled:Boolean=false,val total:Int=0,val unknown:Int=0,@com.google.gson.annotations.SerializedName("cache_capacity") val cacheCapacity:Int=0,@com.google.gson.annotations.SerializedName("cache_algo") val cacheAlgo:String="")
data class NewApiAffinityState(val enabled:Boolean=false,val cache:NewApiAffinityCache=NewApiAffinityCache())
data class NewApiRoutingPolicy(
    val active: Boolean = false,
    val mode: String = "normal",
    @SerializedName("channel_id") val channelId: Int? = null,
    @SerializedName("channel_name") val channelName: String = "",
    @SerializedName("applied_at") val appliedAt: String? = null,
    val phase: String = "idle",
    @SerializedName("channels_count") val channelsCount: Int = 0,
    @SerializedName("restored_count") val restoredCount: Int = 0,
    val error: String = ""
)

fun routingPolicyModeLabel(mode: String): String = when (mode) {
    "preferred" -> "最优先"
    "exclusive" -> "仅使用"
    else -> "正常路由"
}
data class NewApiProbeItem(
    val kind: String = "model",
    val provider: String = "",
    val id: String = "",
    val name: String = "",
    val status: String = "unknown",
    @SerializedName("checked_at") val checkedAt: String? = null,
    @SerializedName("latency_ms") val latencyMs: Long? = null,
    @SerializedName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerializedName("consecutive_successes") val consecutiveSuccesses: Int = 0,
    val evidence: String = ""
)
data class NewApiProbeSummary(
    val total: Int = 0,
    val healthy: Int = 0,
    val degraded: Int = 0,
    val failed: Int = 0,
    val unknown: Int = 0
)
data class NewApiProbeResponse(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("checked_at") val checkedAt: String? = null,
    @SerializedName("probe_status") val probeStatus: String = "unknown",
    @SerializedName("is_stale") val isStale: Boolean = true,
    val summary: NewApiProbeSummary = NewApiProbeSummary(),
    val items: List<NewApiProbeItem> = emptyList()
)

data class NewApiLogItem(
    val id: Long = 0, @SerializedName("created_at") val createdAt: Long = 0,
    val model: String = "", @SerializedName("channel_id") val channelId: Int? = null, @SerializedName("channel_name") val channelName: String = "", val outcome: String = "unknown",
    @SerializedName("token_name") val tokenName: String = "",
    @SerializedName("prompt_tokens") val promptTokens: Long = 0,
    @SerializedName("completion_tokens") val completionTokens: Long = 0,
    val quota: Long = 0, @SerializedName("use_time") val useTime: Long = 0,
    val frt: Double? = null, @SerializedName("reasoning_effort") val reasoningEffort: String = "",
    @SerializedName("use_channel") val useChannel: List<String> = emptyList(),
    @SerializedName("request_path") val requestPath: String = "",
    @SerializedName("log_type") val logType: Int = 0,
    val content: String = ""
)
data class NewApiLogTokens(
    val tokens: List<String> = emptyList(), @SerializedName("scan_limit") val scanLimit: Int = 0
)
data class NewApiLogPage(
    val items: List<NewApiLogItem> = emptyList(), val total: Int = 0,
    val page: Int = 1, @SerializedName("page_size") val pageSize: Int = 30,
    val truncated: Boolean = false, @SerializedName("scan_limit") val scanLimit: Int = 1000
)

// 渠道余额快照（api/newapi/balance，NAS 定时任务写入）
data class NewApiBalanceChannel(
    @SerializedName("usage_detail") val usageDetail: PipioUsageDetail? = null,
    val name: String = "",
    val usd: Double? = null,
    val quota: Double? = null,
    @SerializedName("today_used_usd") val todayUsedUsd: Double? = null,
    @SerializedName("month_start_usd") val monthStartUsd: Double? = null,
    val error: String? = null
)
data class NewApiBalance(
    @SerializedName("updated_at") val updatedAt: String = "",
    val channels: List<NewApiBalanceChannel> = emptyList(),
    val total: Double = 0.0,
    val workbuddy: WorkBuddyPool? = null,
    @SerializedName("workbuddy_error") val workbuddyError: String? = null
)

// Pipio 日报详情（点击余额卡中的 pipio / pipio2 后读取）
data class PipioUsageToken(
    val name: String = "",
    val quota: Double = 0.0,
    val calls: Int = 0,
    @SerializedName("cache_rate") val cacheRate: Double? = null
)

data class PipioUsageDetail(
    val models: List<PipioUsageToken> = emptyList(),
    val name: String = "",
    val display: String = "",
    val ok: Boolean = false,
    val error: String? = null,
    val quota: Double? = null,
    val today: Double? = null,
    val yesterday: Double? = null,
    val week: Double? = null,
    @SerializedName("used_total") val usedTotal: Double? = null,
    @SerializedName("today_calls") val todayCalls: Int? = null,
    @SerializedName("today_errors") val todayErrors: Int? = null,
    val daily: List<Double> = emptyList(),
    val tokens: List<PipioUsageToken> = emptyList(),
    @SerializedName("updated_at") val updatedAt: String? = null
)

// WorkBuddy 账号池（workbuddy2api /status，经 balance 快照透传）
data class WorkBuddyAccount(
    val uid: String = "",
    val nickname: String = "",
    val credits: Int? = null,
    @SerializedName("credits_total") val creditsTotal: Int? = null,
    @SerializedName("daily_cap") val dailyCap: Int? = null,
    @SerializedName("daily_cap_source") val dailyCapSource: String? = null,
    @SerializedName("daily_cap_effective_date") val dailyCapEffectiveDate: String? = null,
    @SerializedName("daily_cap_captured_at") val dailyCapCapturedAt: String? = null,
    @SerializedName("daily_cap_timezone") val dailyCapTimezone: String? = null,
    val realm: String? = null,
    val cooling: Boolean = false,
    @SerializedName("manual_disabled") val manualDisabled: Boolean = false,
    val disabled: Boolean = false,
    @SerializedName("in_flight") val inFlight: Int? = null,
    @SerializedName("success_count") val successCount: Int? = null,
    @SerializedName("last_success") val lastSuccess: String? = null,
    @SerializedName("last_err") val lastErr: String? = null,
    @SerializedName("breaker_fails") val breakerFails: Int? = null,
    @SerializedName("month_used") val monthUsed: Int? = null,
    @SerializedName("today_used") val todayUsed: Int? = null,
    @SerializedName("group_id") val groupId: String = "",
    @SerializedName("recent_requests") val recentRequests: Int? = null,
    @SerializedName("recent_last_ts") val recentLastTs: String? = null
)

// 上游请求流水里的账号（来自 workbuddy2api 容器日志）：
// 上游在请求结束时写出一行「昵称(uid8)」，据此知道刚刚是哪个账号在跑。
data class WorkBuddyRecentAccount(
    val uid: String = "",
    @SerializedName("uid8") val uid8: String = "",
    val nickname: String = "",
    val requests: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val tokens: Int = 0,
    @SerializedName("last_ts") val lastTs: String = "",
    @SerializedName("last_model") val lastModel: String = ""
)

data class WorkBuddyGroup(
    val id: String = "",
    val name: String = "",
    val total: Int = 0,
    val healthy: Int = 0,
    val cooling: Int = 0,
    val disabled: Int = 0,
    @SerializedName("realm_servable") val realmServable: Map<String, Boolean>? = null,
    @SerializedName("last_account") val lastAccount: WorkBuddyRecentAccount? = null,
    @SerializedName("recent_accounts") val recentAccounts: List<WorkBuddyRecentAccount> = emptyList(),
    val accounts: List<WorkBuddyAccount> = emptyList()
)

data class WorkBuddyPool(
    val total: Int = 0,
    val healthy: Int = 0,
    val cooling: Int = 0,
    val disabled: Int = 0,
    @SerializedName("realm_servable") val realmServable: Map<String, Boolean>? = null,
    val accounts: List<WorkBuddyAccount> = emptyList(),
    val groups: List<WorkBuddyGroup> = emptyList()
)

// AnythingLLM 归档任务（api/knowledge/archive 系列端点）
data class ArchiveTask(
    @SerializedName("task_id") val taskId: String = "",
    val id: String = "",
    val kind: String = "",
    val source: String? = null,
    val status: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    val error: String? = null,
    val result: com.google.gson.JsonObject? = null,
    val filename: String? = null,
    val workspace: String = ""
)


// AnythingLLM 问答（api/ask）
data class AskSource(
    val title: String = "",
    val score: Double = 0.0,
    val url: String = ""
)
data class AskResponse(
    val ok: Boolean = false,
    val answer: String = "",
    val sources: List<AskSource> = emptyList(),
    val error: String? = null
)
