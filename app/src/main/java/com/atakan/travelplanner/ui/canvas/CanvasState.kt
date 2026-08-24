package com.atakan.travelplanner.ui.canvas

import kotlinx.serialization.Serializable

@Serializable
data class StickerItem(
    val id: String,
    val assetName: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val zIndex: Int
)

@Serializable
data class CanvasState(
    val stickers: List<StickerItem> = emptyList(),
    val nextZIndex: Int = 0
)

fun CanvasState.withStickerAdded(id: String, assetName: String, x: Float, y: Float): CanvasState {
    val sticker = StickerItem(id = id, assetName = assetName, x = x, y = y, zIndex = nextZIndex)
    return copy(stickers = stickers + sticker, nextZIndex = nextZIndex + 1)
}

fun CanvasState.withStickerRemoved(id: String): CanvasState =
    copy(stickers = stickers.filterNot { it.id == id })

fun CanvasState.withStickerBroughtToFront(id: String): CanvasState {
    val index = stickers.indexOfFirst { it.id == id }
    if (index == -1) return this
    val updated = stickers.toMutableList()
    updated[index] = updated[index].copy(zIndex = nextZIndex)
    return copy(stickers = updated, nextZIndex = nextZIndex + 1)
}

fun CanvasState.withStickerTransformed(
    id: String,
    x: Float,
    y: Float,
    scale: Float,
    rotationDegrees: Float
): CanvasState {
    val index = stickers.indexOfFirst { it.id == id }
    if (index == -1) return this
    val updated = stickers.toMutableList()
    updated[index] = updated[index].copy(x = x, y = y, scale = scale, rotationDegrees = rotationDegrees)
    return copy(stickers = updated)
}
