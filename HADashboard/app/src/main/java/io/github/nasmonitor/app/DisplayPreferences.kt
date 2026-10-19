package io.github.nasmonitor.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class FontScaleOption(val key: String, val label: String, val scale: Float) {
    STANDARD("standard", "标准", 1f),
    LARGE("large", "较大", 1.12f),
    EXTRA_LARGE("extra_large", "特大", 1.24f);

    companion object {
        fun fromKey(value: String?): FontScaleOption = entries.firstOrNull { it.key == value } ?: STANDARD
    }
}

enum class ServerListStyle(val key: String, val label: String) {
    VISUAL("visual", "视觉卡片"),
    COMPACT("compact", "简洁列表");

    companion object {
        fun fromKey(value: String?): ServerListStyle = entries.firstOrNull { it.key == value } ?: VISUAL
    }
}

enum class TaskListDensity(val key: String, val label: String) {
    STANDARD("standard", "标准卡片"),
    COMPACT("compact", "紧凑行");

    companion object {
        fun fromKey(value: String?): TaskListDensity = entries.firstOrNull { it.key == value } ?: STANDARD
    }
}

enum class TaskFieldPreset(val key: String, val label: String, val description: String) {
    STANDARD("standard", "标准", "状态、进度、速度与分享率"),
    LITE("lite", "精简", "只保留状态、进度与上下行速度"),
    DOWNLOAD("download", "下载优先", "突出大小、下载速度与剩余时间"),
    SEEDING("seeding", "做种优先", "突出分享率、上传量与 Peers");

    companion object {
        fun fromKey(value: String?): TaskFieldPreset = entries.firstOrNull { it.key == value } ?: STANDARD
    }
}

/** 全 App 共用的显示偏好；写入 SharedPreferences，同时驱动 Compose 重组。 */
object DisplayPreferences {
    private const val NAME = "display_preferences"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_SERVER_LIST_STYLE = "server_list_style"
    private const val KEY_TASK_DENSITY = "task_list_density"
    private const val KEY_TASK_PRESET = "task_field_preset"
    private const val KEY_TASK_REFRESH = "task_refresh_seconds"
    private const val KEY_TASK_MAX_COUNT = "task_max_count"

    var fontScaleOption by mutableStateOf(FontScaleOption.STANDARD)
        private set
    var serverListStyle by mutableStateOf(ServerListStyle.VISUAL)
        private set
    var taskDensity by mutableStateOf(TaskListDensity.STANDARD)
        private set
    var taskFieldPreset by mutableStateOf(TaskFieldPreset.STANDARD)
        private set
    /** -1 表示跟随全局刷新；0 表示手动；其余为秒。 */
    var taskRefreshSeconds by mutableStateOf(-1)
        private set
    /** 0 表示不限制。 */
    var taskMaxCount by mutableStateOf(0)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        fontScaleOption = FontScaleOption.fromKey(prefs.getString(KEY_FONT_SCALE, null))
        serverListStyle = ServerListStyle.fromKey(prefs.getString(KEY_SERVER_LIST_STYLE, null))
        taskDensity = TaskListDensity.fromKey(prefs.getString(KEY_TASK_DENSITY, null))
        taskFieldPreset = TaskFieldPreset.fromKey(prefs.getString(KEY_TASK_PRESET, null))
        taskRefreshSeconds = prefs.getInt(KEY_TASK_REFRESH, -1).let { if (it in setOf(-1, 0, 5, 10, 30)) it else -1 }
        taskMaxCount = prefs.getInt(KEY_TASK_MAX_COUNT, 0).takeIf { it in setOf(0, 50, 100, 200) } ?: 0
    }

    fun setFontScale(context: Context, option: FontScaleOption) {
        fontScaleOption = option
        write(context) { putString(KEY_FONT_SCALE, option.key) }
    }

    fun setServerListStyle(context: Context, style: ServerListStyle) {
        serverListStyle = style
        write(context) { putString(KEY_SERVER_LIST_STYLE, style.key) }
    }

    fun setTaskDensity(context: Context, density: TaskListDensity) {
        taskDensity = density
        write(context) { putString(KEY_TASK_DENSITY, density.key) }
    }

    fun setTaskFieldPreset(context: Context, preset: TaskFieldPreset) {
        taskFieldPreset = preset
        write(context) { putString(KEY_TASK_PRESET, preset.key) }
    }

    fun setTaskRefreshSeconds(context: Context, seconds: Int) {
        val normalized = if (seconds in setOf(-1, 0, 5, 10, 30)) seconds else -1
        taskRefreshSeconds = normalized
        write(context) { putInt(KEY_TASK_REFRESH, normalized) }
    }

    fun setTaskMaxCount(context: Context, count: Int) {
        val normalized = count.takeIf { it in setOf(0, 50, 100, 200) } ?: 0
        taskMaxCount = normalized
        write(context) { putInt(KEY_TASK_MAX_COUNT, normalized) }
    }

    fun effectiveTaskRefreshSeconds(globalSeconds: Int): Int = when {
        taskRefreshSeconds >= 0 -> taskRefreshSeconds
        globalSeconds > 0 -> globalSeconds
        else -> 0
    }

    private fun write(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().apply(block).apply()
    }
}
