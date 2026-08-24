package com.atakan.travelplanner.ui.canvas

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

interface CanvasStateStore {
    val canvasState: Flow<CanvasState>
    suspend fun loadOnce(): CanvasState
    suspend fun save(state: CanvasState)
}

private val Context.canvasDataStore by preferencesDataStore(name = "canvas_state")
private val CANVAS_STATE_KEY = stringPreferencesKey("canvas_state_json")

class CanvasRepository(private val context: Context) : CanvasStateStore {

    override val canvasState: Flow<CanvasState> = context.canvasDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            CanvasStateSerializer.deserialize(prefs[CANVAS_STATE_KEY] ?: "")
        }

    override suspend fun loadOnce(): CanvasState = canvasState.first()

    override suspend fun save(state: CanvasState) {
        context.canvasDataStore.edit { prefs ->
            prefs[CANVAS_STATE_KEY] = CanvasStateSerializer.serialize(state)
        }
    }
}
