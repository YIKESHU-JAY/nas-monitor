package io.github.nasmonitor.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.nasmonitor.app.data.DownloaderAuth
import io.github.nasmonitor.app.data.DownloaderProtocol
import io.github.nasmonitor.app.data.DownloaderServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private data class DownloaderListResponse(val servers: List<DownloaderServer> = emptyList())

object DownloaderStore {
    private const val PREFS = "downloader_servers"
    private const val KEY = "servers"
    private val gson = Gson()
    private val state = MutableStateFlow<List<DownloaderServer>>(emptyList())
    val servers = state.asStateFlow()

    // 凭据不进入 DownloaderServer；密码修改/删除时递增，供页面感知并刷新连接。
    private val credentialState = MutableStateFlow(0L)
    val credentialEpoch = credentialState.asStateFlow()
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        DownloaderAuth.passwordProvider = { server -> password(context, server.id) }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw != null) {
            runCatching {
                val type = object : TypeToken<DownloaderListResponse>() {}.type
                val parsed: DownloaderListResponse = gson.fromJson(raw, type)
                state.value = parsed.servers
                if (parsed.servers.any { it.order <= 0 }) {
                    val normalized = parsed.servers.sortedWith(
                        compareBy({ if (it.order > 0) it.order else Int.MAX_VALUE })
                    ).mapIndexed { index, server -> server.copy(order = index + 1) }
                    persistServers(context, normalized)
                    state.value = normalized
                }
            }
        }
    }

    fun find(id: String): DownloaderServer? = state.value.firstOrNull { it.id == id }

    /** 按卡片序号排序后的列表；未设置序号的历史数据排在最后。 */
    fun ordered(servers: List<DownloaderServer>): List<DownloaderServer> =
        servers.sortedWith(compareBy({ if (it.order > 0) it.order else Int.MAX_VALUE }))

    fun password(context: Context, id: String): String =
        SecureCredentialStore.get(context, "downloader.$id.password")

    /** 返回保存后的服务器（含生成或清洗后的 id），供调用方导航使用。 */
    @Synchronized
    fun save(
        context: Context,
        server: DownloaderServer,
        password: String,
        previousId: String? = null,
        passwordChanged: Boolean = false
    ): DownloaderServer {
        val existing = state.value.firstOrNull { it.id == (previousId ?: server.id) }
        val protocol = server.protocol
        val trimmedPath = server.rpcPath.trim()
        val path = when {
            trimmedPath.isBlank() -> protocol.defaultPath
            existing != null && trimmedPath == existing.protocol.defaultPath && protocol != existing.protocol -> protocol.defaultPath
            else -> trimmedPath
        }
        val cleaned = server.copy(
            id = server.id.ifBlank { "dl_${java.util.UUID.randomUUID()}" },
            name = server.name.trim(),
            host = server.host.trim(),
            username = server.username.trim(),
            rpcPath = path,
            order = existing?.order?.takeIf { it > 0 } ?: ((state.value.maxOfOrNull { it.order } ?: 0) + 1)
        )
        val current = state.value.toMutableList()
        val index = current.indexOfFirst { it.id == (previousId ?: cleaned.id) }
        if (index >= 0) current[index] = cleaned else current.add(cleaned)

        val passwordKey = "downloader.${cleaned.id}.password"
        val previousPassword = previousId?.takeIf { it != cleaned.id }
            ?.let { SecureCredentialStore.get(context, "downloader.$it.password") }
        val oldPassword = SecureCredentialStore.get(context, "downloader.${previousId ?: cleaned.id}.password")
        when {
            password.isNotBlank() -> SecureCredentialStore.put(context, passwordKey, password)
            passwordChanged -> SecureCredentialStore.remove(context, passwordKey)
            previousPassword != null -> if (previousPassword.isNotBlank()) {
                SecureCredentialStore.put(context, passwordKey, previousPassword)
            }
        }
        if (previousId != null && previousId != cleaned.id) {
            SecureCredentialStore.remove(context, "downloader.$previousId.password")
        }

        persistServers(context, current)
        state.value = current
        // 仅凭据实际变化时递增，避免普通保存触发无谓重连；连接目标/协议变化本身已由 servers 流驱动。
        // 预填旧密码不算变化；只有密码值实际改变、被清空或换了 id 才触发重连。
        val credentialsTouched = (passwordChanged && password != oldPassword) ||
            (previousId != null && previousId != cleaned.id)
        if (credentialsTouched) credentialState.value += 1
        return cleaned
    }

    private fun persistServers(context: Context, servers: List<DownloaderServer>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(DownloaderListResponse(servers))).apply()
    }

    /**
     * 按传入 id 顺序重排卡片，序号从 1 开始连续编号。
     * 仅当 id 集合与当前下载器集合完全一致时生效，避免裁剪列表破坏 order。
     */
    @Synchronized
    fun reorder(context: Context, ids: List<String>): Boolean {
        val current = state.value
        val currentIds = current.map { it.id }.toSet()
        if (ids.size != currentIds.size || ids.toSet() != currentIds) return false
        val positions = ids.withIndex().associate { (index, id) -> id to index + 1 }
        val updated = current.mapNotNull { server ->
            positions[server.id]?.let { server.copy(order = it) }
        }.sortedBy { it.order }
        persistServers(context, updated)
        state.value = updated
        return true
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        state.value = state.value.filterNot { it.id == id }
        SecureCredentialStore.removePrefix(context, "downloader.$id.")
        credentialState.value += 1
        persist(context)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(DownloaderListResponse(state.value))).apply()
    }
}
