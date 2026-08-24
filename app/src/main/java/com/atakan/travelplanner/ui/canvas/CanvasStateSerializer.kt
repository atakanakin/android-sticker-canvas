package com.atakan.travelplanner.ui.canvas

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CanvasStateSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun serialize(state: CanvasState): String = json.encodeToString(state)

    fun deserialize(raw: String): CanvasState =
        if (raw.isBlank()) CanvasState() else json.decodeFromString(raw)
}
