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

    @Test
    fun `withStickerBroughtToFront on an unknown id returns the state unchanged`() {
        val state = CanvasState().withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)

        val result = state.withStickerBroughtToFront("does-not-exist")

        assertEquals(state, result)
    }

    @Test
    fun `withStickerTransformed on an unknown id returns the state unchanged`() {
        val state = CanvasState().withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)

        val result = state.withStickerTransformed(id = "does-not-exist", x = 1f, y = 1f, scale = 2f, rotationDegrees = 10f)

        assertEquals(state, result)
    }

    @Test
    fun `withStickerRemoved on an unknown id returns the state unchanged`() {
        val state = CanvasState().withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)

        val result = state.withStickerRemoved("does-not-exist")

        assertEquals(state, result)
    }

    @Test
    fun `withStickerTransformed does not affect other stickers`() {
        val state = CanvasState()
            .withStickerAdded(id = "a", assetName = "star.svg", x = 0f, y = 0f)
            .withStickerAdded(id = "b", assetName = "heart.svg", x = 5f, y = 5f)

        val result = state.withStickerTransformed(id = "a", x = 42f, y = 84f, scale = 1.5f, rotationDegrees = 30f)

        val untouched = result.stickers.first { it.id == "b" }
        assertEquals(5f, untouched.x)
        assertEquals(5f, untouched.y)
        assertEquals(1f, untouched.scale)
        assertEquals(0f, untouched.rotationDegrees)
    }
}
