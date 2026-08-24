package com.atakan.travelplanner.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasStateTest {

    @Test
    fun `withStickerAdded appends sticker with current nextZIndex and increments counter`() {
        val state = CanvasState()
        val result = state.withStickerAdded(id = "a", assetName = "star.svg", x = 10f, y = 20f)

        assertEquals(1, result.stickers.size)
        val sticker = result.stickers.first()
        assertEquals("a", sticker.id)
        assertEquals("star.svg", sticker.assetName)
        assertEquals(0, sticker.zIndex)
        assertEquals(1, result.nextZIndex)
    }

    @Test
    fun `withStickerRemoved drops only the matching sticker`() {
        val state = CanvasState()
            .withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)
            .withStickerAdded(id = "b", assetName = "heart.svg", x = 0f, y = 0f)

        val result = state.withStickerRemoved("a")

        assertEquals(1, result.stickers.size)
        assertEquals("b", result.stickers.first().id)
    }

    @Test
    fun `withStickerBroughtToFront gives the sticker the highest zIndex`() {
        val state = CanvasState()
            .withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)
            .withStickerAdded(id = "b", assetName = "heart.svg", x = 0f, y = 0f)

        val result = state.withStickerBroughtToFront("a")

        val a = result.stickers.first { it.id == "a" }
        val b = result.stickers.first { it.id == "b" }
        assertTrue(a.zIndex > b.zIndex)
        assertEquals(3, result.nextZIndex)
    }

    @Test
    fun `withStickerTransformed updates position scale and rotation only for the matching sticker`() {
        val state = CanvasState()
            .withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)

        val result = state.withStickerTransformed(
            id = "a",
            x = 42f,
            y = 84f,
            scale = 1.5f,
            rotationDegrees = 30f
        )

        val sticker = result.stickers.first { it.id == "a" }
        assertEquals(42f, sticker.x)
        assertEquals(84f, sticker.y)
        assertEquals(1.5f, sticker.scale)
        assertEquals(30f, sticker.rotationDegrees)
    }
}
