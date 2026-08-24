package com.atakan.travelplanner.ui.canvas

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(initial: CanvasState = CanvasState()) : CanvasStateStore {
        val saved = mutableListOf<CanvasState>()
        private val state = MutableStateFlow(initial)
        override val canvasState: Flow<CanvasState> get() = state
        override suspend fun loadOnce(): CanvasState = state.value
        override suspend fun save(state: CanvasState) {
            saved += state
        }
    }

    private fun viewModel(store: FakeStore, ids: List<String> = listOf("id-1", "id-2", "id-3")): CanvasViewModel {
        var idIndex = 0
        return CanvasViewModel(
            repository = store,
            assetNamesProvider = { listOf("star.svg", "heart.svg") },
            ioDispatcher = dispatcher,
            idProvider = { ids[idIndex++] }
        )
    }

    @Test
    fun `loads persisted state and asset names on init`() = runTest(dispatcher) {
        val persisted = CanvasState().withStickerAdded(id = "existing", assetName = "star.svg", x = 1f, y = 2f)
        val store = FakeStore(initial = persisted)
        val vm = viewModel(store)

        advanceTimeBy(1)
        assertEquals(persisted, vm.canvasState.value)
        assertEquals(listOf("star.svg", "heart.svg"), vm.assetNames.value)
    }

    @Test
    fun `adding a sticker updates state and persists after debounce`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        advanceTimeBy(1)

        vm.onStickerAdded(assetName = "star.svg", x = 10f, y = 20f)

        assertEquals(1, vm.canvasState.value.stickers.size)
        assertEquals(0, store.saved.size)

        advanceTimeBy(350)

        assertEquals(1, store.saved.size)
        assertEquals(vm.canvasState.value, store.saved.last())
    }

    @Test
    fun `rapid consecutive edits collapse into a single persisted write`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        advanceTimeBy(1)

        vm.onStickerAdded(assetName = "star.svg", x = 0f, y = 0f)
        advanceTimeBy(100)
        vm.onStickerTransformed(id = "id-1", x = 5f, y = 5f, scale = 1f, rotationDegrees = 0f)
        advanceTimeBy(100)
        vm.onStickerBroughtToFront("id-1")

        advanceTimeBy(350)

        assertEquals(1, store.saved.size)
    }

    @Test
    fun `removing a sticker drops it from state`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        advanceTimeBy(1)
        vm.onStickerAdded(assetName = "star.svg", x = 0f, y = 0f)

        vm.onStickerRemoved("id-1")

        assertEquals(0, vm.canvasState.value.stickers.size)
    }

    @Test
    fun `clearing the ViewModel flushes a pending debounced save immediately`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = viewModel(store)
        val viewModelStore = ViewModelStore()
        viewModelStore.put("canvas", vm)
        advanceTimeBy(1)

        vm.onStickerAdded(assetName = "star.svg", x = 0f, y = 0f)
        assertEquals(0, store.saved.size)

        viewModelStore.clear()
        runCurrent()

        assertEquals(1, store.saved.size)
        assertEquals(vm.canvasState.value, store.saved.last())
    }
}
