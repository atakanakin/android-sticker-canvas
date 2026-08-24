package com.atakan.travelplanner.ui.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val STICKER_BASE_SIZE: Dp = 96.dp

@Composable
fun StickerView(
    item: StickerItem,
    onGestureStart: (id: String) -> Unit,
    onDrag: (id: String, centerInWindow: Offset) -> Unit,
    onGestureEnd: (id: String, x: Float, y: Float, scale: Float, rotationDegrees: Float, finalCenterInWindow: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val drawableRes = drawableResFor(item.assetName) ?: return

    var x by remember(item.id) { mutableFloatStateOf(item.x) }
    var y by remember(item.id) { mutableFloatStateOf(item.y) }
    var scale by remember(item.id) { mutableFloatStateOf(item.scale) }
    var rotation by remember(item.id) { mutableFloatStateOf(item.rotationDegrees) }
    var layoutCenter by remember(item.id) { mutableStateOf(Offset.Zero) }

    Image(
        painter = painterResource(id = drawableRes),
        contentDescription = item.assetName,
        modifier = modifier
            .size(STICKER_BASE_SIZE)
            .onGloballyPositioned { coordinates ->
                val topLeft = coordinates.localToWindow(Offset.Zero)
                layoutCenter = topLeft + Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
            }
            .graphicsLayer {
                translationX = x
                translationY = y
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            }
            .pointerInput(item.id) {
                detectStickerGestures(
                    onGestureStart = { onGestureStart(item.id) },
                    onGesture = { _, pan, zoom, rotationChange ->
                        x += pan.x
                        y += pan.y
                        scale = (scale * zoom).coerceIn(0.3f, 4f)
                        rotation += rotationChange
                        onDrag(item.id, layoutCenter + Offset(x, y))
                    },
                    onGestureEnd = {
                        onGestureEnd(item.id, x, y, scale, rotation, layoutCenter + Offset(x, y))
                    }
                )
            }
    )
}
