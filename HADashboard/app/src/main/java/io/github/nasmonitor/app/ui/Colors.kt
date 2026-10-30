package io.github.nasmonitor.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

// ── 主题调色板（借鉴 MoviePilot-v3 主题设计）──
// 槽位顺序：AppBg, CardWhite, CardTint, Ink, Muted, Subtle, Track, Blue(主色), BlueSoft(主色柔和)
private val LightPalette = listOf(
    Color(0xFFF5F6FA), Color.White, Color(0xFFF0F2FA), Color(0xFF202532),
    Color(0xFF566174), Color(0xFF566174), Color(0xFFE7EAF1), Color(0xFF5B7CFA), Color(0xFFE9EEFF),
)
private val DarkPalette = listOf(
    Color(0xFF11151D), Color(0xFF1B2230), Color(0xFF242C3A), Color(0xFFF1F5F9),
    Color(0xFFAAB6C8), Color(0xFFAAB6C8), Color(0xFF354052), Color(0xFF7C9BFF), Color(0xFF29375F),
)
// purple 紫夜（MoviePilot v3 默认主题：bg #28243D / surface #312D4B / tint #3D3759 / ink #E7E3FC）
private val PurplePalette = listOf(
    Color(0xFF28243D), Color(0xFF312D4B), Color(0xFF3D3759), Color(0xFFE7E3FC),
    Color(0xFFB6BEE3), Color(0xFFB6BEE3), Color(0xFF4A5072), Color(0xFF8D51F9), Color(0xFF3A3455),
)
// 极光：深墨绿底 + 顶部青绿极光带，卡片用实心深色保证可读
private val AuroraPalette = listOf(
    Color(0xFF050D0F), Color(0xFF0B1A1C), Color(0xFF122A2D), Color(0xFFE6FFF9),
    Color(0xFF5F8F90), Color(0xFF9CC4C1), Color(0xFF1E3A3C), Color(0xFF34E0C0), Color(0xFF0F2E2A),
)
// 日落：暖米白底 + 暖橘 accent，浅色护眼
private val SunsetPalette = listOf(
    Color(0xFFFFF8F0), Color(0xFFFFFFFF), Color(0xFFFFEEDD), Color(0xFF3D2A20),
    Color(0xFF775842), Color(0xFF6F5240), Color(0xFFF5DCC8), Color(0xFFFF7E47), Color(0xFFFFE4D0),
)
// 玻璃页面透出背景；卡片使用独立半透明填充。弹层和导航另用不透明表面。
private val GlassPalette = listOf(
    Color.Transparent, Color(0xCCFFFFFF), Color(0x80FFFFFF), Color(0xFF1A2438),
    Color(0xFF5B6B81), Color(0xFF718198), Color(0x66FFFFFF), Color(0xFF3B82F6), Color(0x59FFFFFF),
)

// ── 玻璃专用辅助色（不被 palette 槽位索引，独立 getter）──
/** 玻璃卡片顶边高光 */
val GlassHighlight get() = Color.White.copy(alpha = 0.22f)
/** 玻璃卡片外描边（弱） */
val GlassBorder get() = Color.White.copy(alpha = 0.10f)
/** 玻璃卡片内层填充（比 CardWhite 更薄，用于嵌套） */
val GlassInnerFill get() = Color.White.copy(alpha = 0.06f)
/** 玻璃 Track（分隔线/边框），跟随半透明主题 */
val GlassTrack get() = Color.White.copy(alpha = 0.32f)

// ── 主题模式与主色（accent）──
private var themeMode by mutableStateOf("light") // light | dark | purple | glass | aurora | sunset
private var accentHex by mutableStateOf("#5B7CFA")

/**
 * Theme/accent key as stored by the app. The six accent swatches remain the
 * same hue; readable foregrounds are derived separately.
 */
internal fun currentThemeKey(): String = themeMode

/** 主题色 6 选（借鉴 MoviePilot 12 色主色机制，裁剪为运维面板适用的 6 色） */
val AccentOptions: List<Pair<String, Color>> = listOf(
    "蓝" to Color(0xFF5B7CFA),
    "紫" to Color(0xFF8D51F9),
    "靛" to Color(0xFF3F51B5),
    "青" to Color(0xFF00BCD4),
    "天蓝" to Color(0xFF16B1FF),
    "青绿" to Color(0xFF009688),
)

fun applyTheme(mode: String) {
    if (mode in setOf("light", "dark", "purple", "glass", "aurora", "sunset")) themeMode = mode
}

fun setAccentColor(hex: String) {
    if (AccentOptions.any { it.second.hex().equals(hex, ignoreCase = true) }) accentHex = hex
}

fun isDarkTheme(): Boolean = isDarkMode
fun isLightTheme(): Boolean = themeMode == "light"
fun isPurpleTheme(): Boolean = themeMode == "purple"

/** 是否为玻璃主题（半透明表面 + 光斑背景） */
fun isGlassTheme(): Boolean = themeMode == "glass"

/** 是否为极光主题（深墨绿底 + 顶部极光带背景） */
fun isAuroraTheme(): Boolean = themeMode == "aurora"

/** 是否为日落主题（暖米白底 + 暖橘 accent） */
fun isSunsetTheme(): Boolean = themeMode == "sunset"

/** 玻璃主题系统栏/底层实色背景（不透明，保证图标可读） */
val GlassSolidBg = Color(0xFF0B1322)

/** 液态玻璃底层背景（亮银蓝对角渐变，光斑叠加其上） */
val GlassGradientBg: List<Color> = listOf(Color(0xFFA9C2E8), Color(0xFF9FB8DE), Color(0xFF8FA8CF), Color(0xFF9CA8DF))

/** 极光主题底层实色背景（极光带绘制在其上） */
val AuroraSolidBg = Color(0xFF050D0F)

/** Color → #RRGGBB */
fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

private val isDarkMode get() = usesDarkSurfaces(themeMode)
private val basePalette: List<Color>
    get() = when (themeMode) {
        "dark" -> DarkPalette
        "purple" -> PurplePalette
        "glass" -> GlassPalette
        "aurora" -> AuroraPalette
        "sunset" -> SunsetPalette
        else -> LightPalette
    }
private val accent: Color
    get() = AccentOptions.firstOrNull { it.second.hex().equals(accentHex, ignoreCase = true) }?.second
        ?: AccentOptions[0].second

val AppBg get() = basePalette[0]
val CardWhite get() = basePalette[1]
val CardTint get() = basePalette[2]
val Ink get() = basePalette[3]
val Muted get() = if (isGlassTheme()) basePalette[4] else readableContent(basePalette[4], surfaceOnBackdrop(EmphasisSurface), maxSteps = 0)
val Subtle get() = if (isGlassTheme()) basePalette[5] else readableContent(basePalette[5], surfaceOnBackdrop(EmphasisSurface), maxSteps = 0)
/** 分隔/描边：玻璃主题下用半透明白 */
val Track get() = if (isGlassTheme()) GlassTrack else basePalette[6]
/**
 * Accent used for text/icons. Hue comes from the user's six options; only
 * lightness is nudged when needed so it stays readable on the synthesized card.
 */
val Blue get() = if (isGlassTheme()) accent else readableContent(accent, surfaceOnBackdrop(EmphasisSurface), maxSteps = 20)
val AccentFill get() = accent
val OnAccent get() = contrastingContent(AccentFill)
/** Text/icon tint that is readable on the accent-soft selection surface. */
val OnAccentSoft get() = if (isGlassTheme()) accent else readableContent(accent, surfaceOnBackdrop(EmphasisSurface), maxSteps = 20)

// 非玻璃卡片的背景锚色：与 ThemeBackdrop 各主题基底一致。
// 卡片按普通 10% / 重点 6% 的背景占比与锚色合成，用于可读性计算。
val CardBackdropAnchor: Color
    get() = when (themeMode) {
        "dark" -> Color(0xFF0B1420)
        "purple" -> Color(0xFF1B1730)
        "aurora" -> Color(0xFF041013)
        "sunset" -> Color(0xFFFDEBDA)
        else -> Color(0xFFE9F2FF)
    }

internal const val CARD_SURFACE_ALPHA = 0.90f
internal const val EMPHASIS_SURFACE_ALPHA = 0.94f
/** Compose alpha is 8-bit; tests use the quantized value so they match what is drawn. */
internal val CARD_SURFACE_DRAWN_ALPHA: Float get() = (CARD_SURFACE_ALPHA * 255f).toInt() / 255f
internal val EMPHASIS_SURFACE_DRAWN_ALPHA: Float get() = (EMPHASIS_SURFACE_ALPHA * 255f).toInt() / 255f

/** 普通卡片：透出约 10% 背景，不把 CardWhite 全局改为半透明。 */
val CardSurface: Color
    get() = if (isGlassTheme()) CardWhite else CardWhite.copy(alpha = CARD_SURFACE_DRAWN_ALPHA)

/** 重点卡片：透出约 6% 背景，用于大标题/数字卡。 */
val EmphasisSurface: Color
    get() = if (isGlassTheme()) CardTint else CardTint.copy(alpha = EMPHASIS_SURFACE_DRAWN_ALPHA)

/** Opaque nested surface: chips/counters inside a translucent card must not stack another translucent white layer. */
val InnerSurface: Color
    get() = if (isGlassTheme()) GlassInnerFill else CardWhite

/** Opaque nested tint for passive blocks such as port metadata. */
val InnerVariant: Color
    get() = if (isGlassTheme()) GlassInnerFill else CardTint

/** Resolve a possibly translucent surface over the theme backdrop for contrast checks. */
internal fun surfaceOnBackdrop(surface: Color, backdrop: Color = CardBackdropAnchor): Color =
    lerp(backdrop, surface.copy(alpha = 1f), surface.alpha)

fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLuminance = foreground.luminance()
    val backgroundLuminance = background.luminance()
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
        (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
}

fun contrastingContent(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

fun readableContent(preferred: Color, background: Color, maxSteps: Int = 10): Color {
    val target = contrastingContent(background)
    // Coarse search keeps the preferred hue; a continuous fallback guarantees
    // the 4.5:1 contract when 20-step quantization would stop just short.
    val steps = maxSteps.coerceIn(0, 20)
    for (step in 0..steps) {
        val candidate = lerp(preferred, target, step / 20f)
        if (contrastRatio(candidate, background) >= 4.5f) return candidate
    }
    var low = steps / 20f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        if (contrastRatio(lerp(preferred, target, mid), background) >= 4.5f) high = mid else low = mid
    }
    return lerp(preferred, target, high)
}
/** 主色柔和底：玻璃主题下让 accent 真正透出来 */
val BlueSoft get() = when {
    isGlassTheme() -> accent.copy(alpha = 0.16f)
    isDarkMode -> lerp(CardSurface, accent, 0.30f)
    else -> lerp(CardSurface, accent, 0.14f)
}
val Green get() = when {
    isGlassTheme() -> Color(0xFF1E9E5A)
    isDarkMode -> Color(0xFF4ADE9A)
    else -> Color(0xFF157A4C)
}
val GreenSoft get() = if (isGlassTheme()) Color(0x331E9E5A) else StatusHealthySurface
val Amber get() = when {
    isGlassTheme() -> Color(0xFFCC8A00)
    isDarkMode -> Color(0xFFF3C969)
    else -> Color(0xFF8A5A00)
}
val AmberSoft get() = if (isGlassTheme()) Color(0x33CC8A00) else StatusAttentionSurface
val Red get() = when {
    isGlassTheme() -> Color(0xFFD64545)
    isDarkMode -> Color(0xFFFF8A8A)
    else -> Color(0xFFB02F35)
}
val RedSoft get() = if (isGlassTheme()) Color(0x33D64545) else StatusCriticalSurface
val Purple = Color(0xFF8B78E6)
val TextPrimary get() = Ink
val TextSecondary get() = Muted
val TextTertiary get() = Subtle
val TextMuted get() = Subtle
val BorderLight get() = Track
val BgDeep get() = AppBg
val BgSurface get() = CardWhite
val Yellow = Amber

// Overview semantic tokens. Components consume intent names rather than palette slots.
val StatusSummarySurface get() = if (isGlassTheme()) lerp(Color.White, accent, 0.14f) else BlueSoft
val StatusSummaryInk get() = readableContent(Blue, StatusSummarySurface)
val StatusHealthySurface get() = when {
    isGlassTheme() -> Color(0xFFE5F7EF)
    isDarkMode -> Color(0xFF173A2D)
    else -> Color(0xFFDDF3E7)
}
val StatusHealthyInk get() = readableContent(Green, StatusHealthySurface, maxSteps = 20)
val StatusAttentionSurface get() = when {
    isGlassTheme() -> Color(0xFFFFF4D9)
    isDarkMode -> Color(0xFF3A3018)
    else -> Color(0xFFFFF0C9)
}
val StatusAttentionInk get() = readableContent(Amber, StatusAttentionSurface, maxSteps = 20)
val StatusCriticalSurface get() = when {
    isGlassTheme() -> Color(0xFFFFE8E8)
    isDarkMode -> Color(0xFF3D2026)
    else -> Color(0xFFFFE2E2)
}
val StatusCriticalInk get() = readableContent(Red, StatusCriticalSurface, maxSteps = 20)

// 专用表面色：终端、日志与图表不再在页面中散落硬编码色值。
val TerminalBg get() = if (isGlassTheme()) Color(0xFF101827) else Color(0xFF151A22)
val TerminalFg get() = Color(0xFFE5EAF2)
val LogPanelBg get() = if (isDarkTheme()) Color(0xFF151A22) else Color(0xFF1E1F24)
val LogPanelFg get() = Color(0xFFE8E8EC)
val ChartGrid get() = if (isDarkTheme()) Color(0x26FFFFFF) else Color(0x1A000000)

fun usageColor(value: Double): Color = when { value >= 90 -> Red; value >= 75 -> Amber; else -> Green }
fun usageSoft(value: Double): Color = when { value >= 90 -> RedSoft; value >= 75 -> AmberSoft; else -> GreenSoft }

// ── Type Scale（第二波新增）──

/** 页面大标题（如设置、工具） */
val TextH1: TextUnit = 22.sp
/** 卡片标题 / 区块标题 */
val TextH2: TextUnit = 17.sp
/** 正文 / 主要数值 */
val TextBody: TextUnit = 14.sp
/** 辅助说明 / 次要数值 */
val TextCaption: TextUnit = 12.sp
/** 小标签 / 状态徽章 */
val TextTiny: TextUnit = 10.sp
/** 大数字强调（如 CPU 百分比） */
val TextDisplay: TextUnit = 24.sp

/** Floating surfaces must occlude the text behind them, even in the glass theme. */
val OverlaySurface get() = if (isGlassTheme()) Color(0xF2F4F9FC) else CardWhite
val OverlayVariant get() = if (isGlassTheme()) Color(0xF2E8EDF7) else CardTint
val NavigationSurface get() = if (isGlassTheme()) Color(0xF7FAFDFE) else CardWhite
