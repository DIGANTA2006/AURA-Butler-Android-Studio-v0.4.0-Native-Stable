package `in`.diganta.aurabutler.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import `in`.diganta.aurabutler.ui.theme.AuraCream
import `in`.diganta.aurabutler.ui.theme.AuraGold
import `in`.diganta.aurabutler.ui.theme.AuraGreen
import `in`.diganta.aurabutler.ui.theme.AuraGreenSoft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A slow, low-contrast colour field drawn behind the whole app. Glass surfaces
 * placed on top of it pick up its colour, so they read as translucent without
 * a costly backdrop blur. Works on minSdk 26 with plain radial gradients.
 */
@Composable
fun AuraGlassBackdrop(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) darkPalette() else lightPalette()
    val transition = rememberInfiniteTransition(label = "aura-backdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "backdrop-phase"
    )

    Box(modifier = Modifier.fillMaxSize().background(palette.base)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (blob in palette.blobs) {
                val angle = (phase + blob.offset) * 2f * PI.toFloat()
                val center = Offset(
                    x = size.width * (blob.anchorX + 0.12f * cos(angle)),
                    y = size.height * (blob.anchorY + 0.10f * sin(angle))
                )
                val radius = size.minDimension * blob.radius
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(blob.color, Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
        content()
    }
}

private class Blob(
    val color: Color,
    val anchorX: Float,
    val anchorY: Float,
    val radius: Float,
    val offset: Float
)

private class Palette(val base: Color, val blobs: List<Blob>)

private fun lightPalette() = Palette(
    base = AuraCream,
    blobs = listOf(
        Blob(AuraGreenSoft.copy(alpha = 0.30f), 0.15f, 0.20f, 0.55f, 0.00f),
        Blob(AuraGold.copy(alpha = 0.26f), 0.85f, 0.30f, 0.50f, 0.35f),
        Blob(Color(0xFFBFE3D2).copy(alpha = 0.50f), 0.55f, 0.85f, 0.60f, 0.65f),
        Blob(Color(0xFFFFE8A9).copy(alpha = 0.38f), 0.20f, 0.90f, 0.45f, 0.80f)
    )
)

private fun darkPalette() = Palette(
    base = Color(0xFF111410),
    blobs = listOf(
        Blob(AuraGreenSoft.copy(alpha = 0.55f), 0.15f, 0.20f, 0.55f, 0.00f),
        Blob(AuraGreen.copy(alpha = 0.85f), 0.85f, 0.35f, 0.60f, 0.35f),
        Blob(AuraGold.copy(alpha = 0.14f), 0.55f, 0.85f, 0.45f, 0.65f),
        Blob(Color(0xFF244F40).copy(alpha = 0.60f), 0.20f, 0.90f, 0.50f, 0.80f)
    )
)
