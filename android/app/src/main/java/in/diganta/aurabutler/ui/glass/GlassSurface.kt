package `in`.diganta.aurabutler.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Translucent "liquid glass" container. The fill is a soft vertical gradient,
 * the border is a brighter top edge that fades out (a specular highlight), and
 * the shadow is kept light so the surface floats without looking opaque.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()

    val fillColors = if (dark) {
        listOf(Color(0x2EFFFFFF), Color(0x12FFFFFF))
    } else {
        listOf(Color(0xB8FFFFFF), Color(0x73FFFFFF))
    }
    val edgeColors = if (dark) {
        listOf(Color(0x66FFFFFF), Color(0x0FFFFFFF))
    } else {
        listOf(Color(0xE6FFFFFF), Color(0x40FFFFFF))
    }

    val fill = Brush.verticalGradient(colors = fillColors)
    val edge = Brush.verticalGradient(colors = edgeColors)

    Box(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x1F000000),
                spotColor = Color(0x2E000000)
            )
            .clip(shape)
            .background(brush = fill, shape = shape)
            .border(width = 1.dp, brush = edge, shape = shape),
        content = content
    )
}
