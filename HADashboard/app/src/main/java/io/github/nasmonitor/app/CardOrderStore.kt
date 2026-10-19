package io.github.nasmonitor.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 服务器页卡片顺序（服务器与下载器共用）。key 形如 `server:nas`、`downloader:dl_123`。 */
object CardOrderStore {
    private const val PREFS = "card_order"
    private const val KEY = "keys"
    private val gson = Gson()
    private val state = MutableStateFlow<List<String>>(emptyList())
    val keys = state.asStateFlow()
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed: List<String> = gson.fromJson(raw, type)
            state.value = parsed
        }
    }

    /** 按已保存顺序排列；未记录的 key 保持原有相对顺序追加到末尾。 */
    fun <T> apply(items: List<T>, saved: List<String>, keyOf: (T) -> String): List<T> {
        val positions = saved.withIndex().associate { (index, key) -> key to index }
        return items.sortedWith(compareBy { positions[keyOf(it)] ?: Int.MAX_VALUE })
    }

    fun <T> apply(items: List<T>, keyOf: (T) -> String): List<T> = apply(items, state.value, keyOf)

    fun saveOrder(keys: List<String>) {
        val ctx = context ?: return
        val cleaned = keys.filter { it.isNotBlank() }.distinct()
        if (cleaned == state.value) return
        state.value = cleaned
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(cleaned)).apply()
    }

    /** 删除已不存在的卡片 key，并把新卡片追加到末尾。 */
    fun retain(validKeys: Set<String>) {
        val current = state.value.filter { it in validKeys }
        val missing = validKeys.filterNot { it in current }
        saveOrder(current + missing)
    }

    fun move(keys: List<String>, from: Int, to: Int): List<String> {
        if (from !in keys.indices || to !in keys.indices || from == to) return keys
        val mutable = keys.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        return mutable
    }
}
