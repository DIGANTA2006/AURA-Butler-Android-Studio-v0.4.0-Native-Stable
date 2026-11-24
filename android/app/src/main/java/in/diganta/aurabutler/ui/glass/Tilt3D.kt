package `in`.diganta.aurabutler.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Tilts the element in 3D toward the finger while it is dragged across it, then
 * springs back flat on release. Opt-in per element. Do not put it on items inside
 * a scrolling list, because the drag gesture consumes the scroll.
 */
fun Modifier.tilt3D(maxDegrees: Float = 8f): Modifier = composed {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) } // normalised -1..1
    var active by remember { mutableStateOf(false) }

    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val rotX by animateFloatAsState(
        targetValue = if (active) -pointer.y * maxDegrees else 0f,
        animationSpec = springSpec,
        label = "tilt-x"
    )
    val rotY by animateFloatAsState(
        targetValue = if (active) pointer.x * maxDegrees else 0f,
        animationSpec = springSpec,
        label = "tilt-y"
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 0.98f else 1f,
        animationSpec = springSpec,
        label = "tilt-scale"
    )

    this
        .onSizeChanged { sizePx = it }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    active = true
                    pointer = toNormalised(offset, sizePx)
                },
                onDragEnd = {
                    active = false
                    pointer = Offset.Zero
                },
                onDragCancel = {
                    active = false
                    pointer = Offset.Zero
                },
                onDrag = { change, _ ->
                    change.consume()
                    pointer = toNormalised(change.position, sizePx)
                }
            )
        }
        .graphicsLayer {
            rotationX = rotX
            rotationY = rotY
            scaleX = scale
            scaleY = scale
            cameraDistance = 12f * density
        }
}

private fun toNormalised(point: Offset, size: IntSize): Offset {
    if (size.width == 0 || size.height == 0) return Offset.Zero
    val x = (point.x / size.width) * 2f - 1f
    val y = (point.y / size.height) * 2f - 1f
    return Offset(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
}
