package com.atakan.travelplanner.ui.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val PERSIST_DEBOUNCE_MS = 300L

class CanvasViewModel(
    private val repository: CanvasStateStore,
    private val assetNamesProvider: suspend () -> List<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idProvider: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _canvasState = MutableStateFlow(CanvasState())
    val canvasState: StateFlow<CanvasState> = _canvasState.asStateFlow()

    private val _assetNames = MutableStateFlow<List<String>>(emptyList())
    val assetNames: StateFlow<List<String>> = _assetNames.asStateFlow()

    private var persistJob: Job? = null
    private val externalScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        viewModelScope.launch(ioDispatcher) {
            _canvasState.value = repository.loadOnce()
        }
        viewModelScope.launch(ioDispatcher) {
            _assetNames.value = assetNamesProvider()
        }
    }

    fun onStickerAdded(assetName: String, x: Float, y: Float) {
        _canvasState.value = _canvasState.value.withStickerAdded(
            id = idProvider(),
            assetName = assetName,
            x = x,
            y = y
        )
        schedulePersist()
    }

    fun onStickerTransformed(id: String, x: Float, y: Float, scale: Float, rotationDegrees: Float) {
        _canvasState.value = _canvasState.value.withStickerTransformed(id, x, y, scale, rotationDegrees)
        schedulePersist()
    }

    fun onStickerBroughtToFront(id: String) {
        _canvasState.value = _canvasState.value.withStickerBroughtToFront(id)
        schedulePersist()
    }

    fun onStickerRemoved(id: String) {
        _canvasState.value = _canvasState.value.withStickerRemoved(id)
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(ioDispatcher) {
            delay(PERSIST_DEBOUNCE_MS)
            repository.save(_canvasState.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        persistJob?.cancel()
        externalScope.launch {
            repository.save(_canvasState.value)
        }
    }
}
