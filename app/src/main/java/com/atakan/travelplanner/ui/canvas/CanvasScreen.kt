package com.atakan.travelplanner.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun CanvasScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: CanvasViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                CanvasViewModel(
                    repository = CanvasRepository(context.applicationContext),
                    assetNamesProvider = { STICKER_CATALOG.map { it.name } }
                )
            }
        }
    )

    val canvasState by viewModel.canvasState.collectAsState()
    val assetNames by viewModel.assetNames.collectAsState()

    var isPickerOpen by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            canvasState.stickers.forEach { sticker ->
                key(sticker.id) {
                    StickerView(
                        item = sticker,
                        modifier = Modifier.zIndex(sticker.zIndex.toFloat()),
                        onGestureStart = { id ->
                            viewModel.onStickerBroughtToFront(id)
                            isDragging = true
                        },
                        onGestureEnd = { id, x, y, scale, rotationDegrees, finalCenterInWindow ->
                            val bounds = trashBounds
                            if (bounds != null && bounds.contains(finalCenterInWindow)) {
                                viewModel.onStickerRemoved(id)
                            } else {
                                viewModel.onStickerTransformed(id, x, y, scale, rotationDegrees)
                            }
                            isDragging = false
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { isPickerOpen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Sticker ekle")
        }

        if (isDragging) {
            FloatingActionButton(
                onClick = {},
                containerColor = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .size(56.dp)
                    .onGloballyPositioned { coordinates ->
                        trashBounds = coordinates.boundsInWindow()
                    }
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Sil")
            }
        }

        if (isPickerOpen) {
            StickerPickerSheet(
                assetNames = assetNames,
                onStickerPicked = { assetName ->
                    viewModel.onStickerAdded(assetName = assetName, x = 200f, y = 400f)
                    isPickerOpen = false
                },
                onDismiss = { isPickerOpen = false }
            )
        }
    }
}
