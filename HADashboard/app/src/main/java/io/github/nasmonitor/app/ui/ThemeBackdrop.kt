package io.github.nasmonitor.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

val PageBackground: Color
    get() = Color.Transparent

@Composable
fun ThemeBackdrop(modifier: Modifier = Modifier) {
    val animated = isAuroraTheme() || isSunsetTheme()
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "themeBackdrop")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
            label = "themeBackdropPhase"
        )
        value
    } else 0f

    Box(modifier.fillMaxSize().background(baseBrush())) {
        Canvas(Modifier.fillMaxSize()) {
            // 顺序关键：先判 aurora/sunset/purple/dark，最后 else 才是浅色。
            // 之前 aurora 落在 else 导致极光和浅色一样，已修复。
            when {
                isAuroraTheme() -> drawAurora(phase)
                isSunsetTheme() -> drawSunset(phase)
                isPurpleTheme() -> drawPurpleNight()
                isDarkTheme() -> drawDeepNight()
                else -> drawLightPaper()
            }
        }
    }
}

private fun baseBrush(): Brush = when {
    isAuroraTheme() -> Brush.verticalGradient(listOf(Color(0xFF020B0D), Color(0xFF06181B), Color(0xFF041013)))
    isSunsetTheme() -> Brush.verticalGradient(listOf(Color(0xFFFFF8F0), Color(0xFFFFE9D8), Color(0xFFFFF4EA)))
    isPurpleTheme() -> Brush.verticalGradient(listOf(Color(0xFF151126), Color(0xFF26183D), Color(0xFF100E20)))
    isDarkTheme() -> Brush.verticalGradient(listOf(Color(0xFF090F19), Color(0xFF121D31), Color(0xFF080C14)))
    else -> Brush.verticalGradient(listOf(Color(0xFFF5FAFF), Color(0xFFE1EEFF), Color(0xFFF5F0FF)))
}

// ── 浅色：淡蓝紫径向光晕 + 细网格，干净通透（无波浪带）──
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLightPaper() {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB4D9FF).copy(alpha = 0.35f), Color.Transparent)), radius = size.width * 0.65f, center = Offset(size.width * 0.15f, size.height * 0.10f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFC9B8F4).copy(alpha = 0.28f), Color.Transparent)), radius = size.width * 0.70f, center = Offset(size.width * 0.90f, size.height * 0.30f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF8BD7E8).copy(alpha = 0.20f), Color.Transparent)), radius = size.width * 0.60f, center = Offset(size.width * 0.50f, size.height * 0.85f))
    drawGrid(Color(0xFF5B7CFA).copy(alpha = 0.035f))
}

// ── 深色：深蓝紫光晕 + 细网格（无波浪带）──
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDeepNight() {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF315AAB).copy(alpha = 0.36f), Color.Transparent)), radius = size.width * 0.78f, center = Offset(size.width * 0.14f, size.height * 0.16f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF6B3F9D).copy(alpha = 0.23f), Color.Transparent)), radius = size.width * 0.76f, center = Offset(size.width * 0.92f, size.height * 0.72f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF4A79C8).copy(alpha = 0.12f), Color.Transparent)), radius = size.width * 0.70f, center = Offset(size.width * 0.50f, size.height * 0.45f))
    drawGrid(Color.White.copy(alpha = 0.025f))
}

// ── 紫夜：紫光晕 + 星点（无波浪带）──
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPurpleNight() {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB06CFF).copy(alpha = 0.25f), Color.Transparent)), radius = size.width * 0.67f, center = Offset(size.width * 0.80f, size.height * 0.13f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF4F86FF).copy(alpha = 0.17f), Color.Transparent)), radius = size.width * 0.72f, center = Offset(size.width * 0.12f, size.height * 0.67f))
    for (index in 0 until 34) {
        val x = ((index * 73) % 100) / 100f
        val y = ((index * 47 + 11) % 100) / 100f
        val radius = if (index % 5 == 0) 2.2f else 1.1f
        drawCircle(Color.White.copy(alpha = if (index % 3 == 0) 0.42f else 0.18f), radius = radius, center = Offset(size.width * x, size.height * y))
    }
}

// ── 极光：真实极光光带（帘幕状，非波浪带），青绿/蓝紫多层，带微光晕 ──
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAurora(phase: Float) {
    // 顶部大范围微光晕：略收敛，保留极光辨识度
    drawCircle(Brush.radialGradient(listOf(Color(0xFF24D8B2).copy(alpha = 0.13f), Color.Transparent)), radius = size.width * 0.88f, center = Offset(size.width * 0.50f, size.height * 0.06f))
    // 三层极光帘幕
    drawCurtain(baseY = 0.03f, height = 0.52f, lean = 0.10f, color = Color(0xFF39E0BC), alpha = 0.27f, phase = phase, freq = 1.0f)
    drawCurtain(baseY = 0.10f, height = 0.46f, lean = -0.14f, color = Color(0xFF4E8CFF), alpha = 0.20f, phase = phase * 0.7f, freq = 1.3f)
    drawCurtain(baseY = 0.18f, height = 0.40f, lean = 0.06f, color = Color(0xFF9A6BFF), alpha = 0.15f, phase = phase * 0.45f, freq = 0.8f)
    // 底部深色地平光
    drawRect(
        brush = Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF02090B).copy(alpha = 0.55f))),
        topLeft = Offset(0f, size.height * 0.72f),
        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.28f)
    )
}

// ── 日落：保留太阳，去掉波浪带，改暖色光晕 ──
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSunset(phase: Float) {
    val sunX = size.width * (0.78f + 0.02f * sin(phase * 0.35f))
    val sunY = size.height * 0.18f
    // 太阳与暖色光晕轻微收敛，保留日落主题差异
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB25F).copy(alpha = 0.64f), Color(0xFFFFB25F).copy(alpha = 0.08f), Color.Transparent)), radius = size.width * 0.60f, center = Offset(sunX, sunY))
    drawCircle(Color(0xFFFFB15B).copy(alpha = 0.54f), radius = size.width * 0.12f, center = Offset(sunX, sunY))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFA06C).copy(alpha = 0.16f), Color.Transparent)), radius = size.width * 0.68f, center = Offset(size.width * 0.15f, size.height * 0.60f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF58AB8).copy(alpha = 0.14f), Color.Transparent)), radius = size.width * 0.65f, center = Offset(size.width * 0.85f, size.height * 0.85f))
}

/**
 * 画一层朦胧极光雾：多个大半径半透明圆横向漂移叠加，纵向渐变淡出。
 * 没有硬边，没有波浪/栅栏，整体是柔和的极光光晕。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurtain(
    baseY: Float,
    height: Float,
    lean: Float,
    color: Color,
    alpha: Float,
    phase: Float,
    freq: Float
) {
    val bandH = size.height * height
    val topY = size.height * baseY
    val blobs = 9
    val blobW = size.width / (blobs - 2).toFloat()
    for (i in 0 until blobs) {
        val cx = i * blobW - blobW * 0.5f + size.width * lean * (i / blobs.toFloat() - 0.5f)
        val wobble = sin(phase * freq + i * PI / 4.5).toFloat() * bandH * 0.10f
        val cy = topY + bandH * 0.35f + wobble
        val radius = blobW * (0.85f + 0.25f * sin(phase * freq * 0.7f + i * PI / 3.0).toFloat())
        drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.4f), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius,
            center = Offset(cx, cy)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(color: Color) {
    val spacing = size.width / 7f
    for (index in 1..6) {
        drawLine(color, Offset(spacing * index, 0f), Offset(spacing * index, size.height), strokeWidth = 1f)
        drawLine(color, Offset(0f, spacing * index), Offset(size.width, spacing * index), strokeWidth = 1f)
    }
}
