package com.atakan.travelplanner.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasStateSerializerTest {

    @Test
    fun `serializing then deserializing returns an equal CanvasState`() {
        val original = CanvasState()
            .withStickerAdded(id = "a", assetName = "star.svg", x = 10f, y = 20f)
            .withStickerAdded(id = "b", assetName = "heart.svg", x = 30f, y = 40f)
            .withStickerTransformed(id = "a", x = 15f, y = 25f, scale = 1.2f, rotationDegrees = 45f)

        val json = CanvasStateSerializer.serialize(original)
        val restored = CanvasStateSerializer.deserialize(json)

        assertEquals(original, restored)
    }

    @Test
    fun `deserializing a blank string returns an empty CanvasState`() {
        val restored = CanvasStateSerializer.deserialize("")

        assertEquals(CanvasState(), restored)
    }
}
