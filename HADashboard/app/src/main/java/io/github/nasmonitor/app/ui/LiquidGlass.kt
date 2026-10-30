package io.github.nasmonitor.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private class GlassScene(val bitmap: Bitmap, val size: IntSize, val origin: Offset)
private val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }

@Composable
fun LiquidGlassHost(content: @Composable BoxScope.() -> Unit) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val enabled = isGlassTheme()
    val wallpaper = remember(viewport, enabled) {
        if (enabled && viewport.width > 0 && viewport.height > 0) {
            createGlassWallpaper(viewport)
        } else null
    }
    val scene = remember(wallpaper, viewport, origin) {
        wallpaper?.let { GlassScene(it, viewport, origin) }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }
        .onGloballyPositioned { origin = it.positionInRoot() }) {
        if (scene != null) {
            val backdrop = remember(scene) { scene.bitmap.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                drawImage(backdrop, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
        }
        CompositionLocalProvider(LocalGlassScene provides scene) { content() }
    }
}

private fun createGlassWallpaper(viewport: IntSize): Bitmap {
    val width = 480
    val height = (width.toFloat() * viewport.height / viewport.width).toInt().coerceIn(240, 1100)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
        intArrayOf(0xFFE4F3FF.toInt(), 0xFFB7D8FA.toInt(), 0xFFE3DCF8.toInt()), null, Shader.TileMode.CLAMP)
    canvas.drawPaint(paint)
    canvas.save()
    canvas.scale(width.toFloat(), height.toFloat())
    val ribbons = listOf(
        Triple(-0.20f, 0xFF9EB7F4.toInt(), 0xFFD6C8F5.toInt()),
        Triple(0.18f, 0xFF69C9E5.toInt(), 0xFFB9EBF0.toInt()),
        Triple(0.58f, 0xFF99A9E8.toInt(), 0xFFDBD0F7.toInt())
    )
    for ((start, first, second) in ribbons) {
        val path = android.graphics.Path().apply {
            moveTo(-0.25f, start)
            cubicTo(1.3f, start + 0.04f, -0.3f, start + 0.42f, 1.25f, start + 0.50f)
            lineTo(1.25f, start + 0.72f)
            cubicTo(-0.55f, start + 0.50f, 1.15f, start + 0.16f, -0.25f, start + 0.10f)
            close()
        }
        paint.shader = LinearGradient(0f, start, 0.9f, start + 0.65f,
            intArrayOf(first, second, 0xFFEAF7FF.toInt()), null, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.color = 0x65FFFFFF
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.0025f
        canvas.drawPath(path, paint)
    }
    canvas.restore()
    return bitmap
}

fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(24.dp),
    navigation: Boolean = false,
    elevation: Dp = 3.dp,
    tintColor: Color = Color.White
): Modifier = composed {
    val scene = LocalGlassScene.current
    if (!isGlassTheme() || scene == null) return@composed this
    var position by remember { mutableStateOf(Offset.Zero) }
    val tint = if (navigation) 0.48f else 0.38f
    val rimBrush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.90f),
        Color.White.copy(alpha = 0.16f), Color(0xFF688FB7).copy(alpha = 0.18f), Color.White.copy(alpha = 0.72f)))
    this.onGloballyPositioned { position = it.positionInRoot() - scene.origin }
        .shadow(elevation, shape, clip = false, ambientColor = Color(0xFF37577B), spotColor = Color(0xFF37577B))
        .clip(shape)
        .drawWithCache {
            val sampleScale = min(0.5f, 420f / max(size.width, size.height).coerceAtLeast(1f))
            val sampleWidth = max(1, ceil(size.width * sampleScale).toInt())
            val sampleHeight = max(1, ceil(size.height * sampleScale).toInt())
            val sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
            val sampling = BitmapShader(scene.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val sampleCanvas = android.graphics.Canvas(sample)
            val transform = Matrix()
            var sampledPosition: Offset? = null
            val divisions = 24
            val vertices = FloatArray((divisions + 1) * (divisions + 1) * 2)
            val rim = 22.dp.toPx()
            for (row in 0..divisions) {
                for (column in 0..divisions) {
                    val vertex = (row * (divisions + 1) + column) * 2
                    vertices[vertex] = refractedAxis(size.width * column / divisions, size.width, rim)
                    vertices[vertex + 1] = refractedAxis(size.height * row / divisions, size.height, rim)
                }
            }
            val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f),
                Color.Transparent, Color.White.copy(alpha = 0.05f)), Offset.Zero, Offset(size.width, size.height))
            onDrawBehind {
                val currentPosition = position
                if (sampledPosition != currentPosition) {
                    transform.reset()
                    transform.setScale(scene.size.width.toFloat() / scene.bitmap.width * sampleScale,
                        scene.size.height.toFloat() / scene.bitmap.height * sampleScale)
                    transform.postTranslate(-currentPosition.x * sampleScale, -currentPosition.y * sampleScale)
                    sampling.setLocalMatrix(transform)
                    paint.shader = sampling
                    sampleCanvas.drawRect(0f, 0f, sampleWidth.toFloat(), sampleHeight.toFloat(), paint)
                    paint.shader = null
                    sampledPosition = currentPosition
                }
                drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(sample, divisions, divisions, vertices, 0, null, 0, paint) }
                drawRect(tintColor.copy(alpha = tint))
                drawRect(sheen)
            }
        }
        .border(0.8.dp, rimBrush, shape)
}

@Composable
fun LiquidAwareCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    colors: CardColors = CardDefaults.cardColors(containerColor = CardWhite),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    glassTint: Color = Color.White,
    translucentSurface: Boolean? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = isGlassTheme() && LocalGlassScene.current != null
    val ordinaryCard = CardDefaults.cardColors(containerColor = CardWhite)
    val useTranslucentSurface = resolveNonGlassTranslucency(
        requestedIsOrdinaryCard = colors == ordinaryCard,
        translucentSurface = translucentSurface
    )
    val renderedElevation = if (useTranslucentSurface) {
        CardDefaults.cardElevation(defaultElevation = NON_GLASS_CARD_ELEVATION)
    } else elevation
    Card(
        modifier = if (glass) modifier.liquidGlass(shape, tintColor = glassTint) else modifier,
        shape = shape,
        colors = if (glass) CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = Ink)
        else if (useTranslucentSurface) CardDefaults.cardColors(containerColor = CardSurface, contentColor = Ink)
        else colors,
        elevation = if (glass) CardDefaults.cardElevation(0.dp) else renderedElevation,
        border = if (glass) null else border,
        content = content
    )
}

/**
 * Non-glass card surface policy. CardColors does not expose its container
 * color, so the caller's colors are compared with the ordinary CardWhite
 * defaults. A null [translucentSurface] upgrades only that ordinary card;
 * any explicit semantic CardColors stays untouched. true forces the
 * translucent surface and false always preserves the requested colors.
 */
internal fun resolveNonGlassTranslucency(
    requestedIsOrdinaryCard: Boolean,
    translucentSurface: Boolean?
): Boolean = when (translucentSurface) {
    true -> true
    false -> false
    null -> requestedIsOrdinaryCard
}

/** Translucent ordinary cards must not add an elevation/tint layer. */
internal val NON_GLASS_CARD_ELEVATION = 0.dp
