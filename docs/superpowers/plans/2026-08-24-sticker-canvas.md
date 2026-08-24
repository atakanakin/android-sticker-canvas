# Sticker Canvas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a full-screen sticker canvas (Instagram Story sticker editor style) reachable from the home screen, where users pick SVG stickers from a bottom-sheet grid, then move/scale/rotate/overlap/delete them on the canvas, with the layout persisted across app restarts.

**Architecture:** MVVM with a `CanvasViewModel` (`StateFlow<CanvasState>`) backed by a DataStore-based `CanvasRepository`. Gesture handling and rendering are pure Jetpack Compose (`Modifier.pointerInput` + `Modifier.graphicsLayer`) — no third-party sticker/gesture library. Stickers are existing `res/drawable` vector resources rendered via `painterResource`, resolved through a small catalog file — no image-loading library needed.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), AndroidX Navigation Compose, AndroidX Lifecycle ViewModel Compose, AndroidX DataStore Preferences, kotlinx.serialization.

**Spec:** `docs/superpowers/specs/2026-08-24-sticker-canvas-design.md`

## Global Constraints

- No third-party sticker/gesture library is introduced — gestures use Compose Foundation's own pointer-input APIs (per spec §2).
- `zIndex` bring-to-front is a counter bump, never a list reorder (spec §3, §6).
- Live gesture updates (position/scale/rotation while a finger is down) stay in local Compose state and drive rendering only via `Modifier.graphicsLayer`; they must never write to the ViewModel's `StateFlow` or trigger persistence until the gesture ends (spec §6).
- All DataStore reads/writes run on `Dispatchers.IO`, and persistence writes are debounced (~300ms) so a burst of edits collapses into one disk write (spec §4, §6).
- Each `StickerItem` renders as its own composable, iterated with an explicit `key(item.id)`, so editing one sticker never forces recomposition of its siblings (spec §6).
- Sticker artwork is already a compile-time `res/drawable` vector resource (12 exist: `stamp_1`…`stamp_9`, `stump_10`, `maple_leaf`, `approve_stamp`). Rendering uses `painterResource` only — no Coil, no runtime SVG parsing, no image-loading dependency (spec §5, §8).
- The catalog mapping sticker string-keys to drawable resource ints lives in exactly one file, `StickerCatalog.kt` — adding a sticker means one new drawable plus one new line there (spec §5).

---

### Task 1: Add dependencies and Gradle plugins

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `libs.androidx.navigation.compose`, `libs.androidx.lifecycle.viewmodel.compose`, `libs.androidx.datastore.preferences`, `libs.kotlinx.serialization.json`, `libs.androidx.compose.material.icons.core`, `libs.kotlinx.coroutines.test`, `libs.plugins.kotlin.serialization` — used by every later task.

- [ ] **Step 1: Add new version and library entries to the catalog**

Edit `gradle/libs.versions.toml`. Add to `[versions]` (after the existing `composeBom` line):

```toml
navigationCompose = "2.9.8"
datastorePreferences = "1.2.0"
kotlinxSerializationJson = "1.9.0"
kotlinxCoroutinesTest = "1.11.0"
```

Add to `[libraries]` (after the existing `androidx-compose-material3` line):

```toml
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }
androidx-compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }
```

Add to `[plugins]`:

```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Register the serialization plugin at the root**

Edit `build.gradle.kts`, add the plugin as `apply false` alongside the existing two:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

- [ ] **Step 3: Apply the plugin and add dependencies in the app module**

Edit `app/build.gradle.kts`. Add the plugin:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
```

Add to the `dependencies` block (alongside the existing `implementation(...)` lines):

```kotlin
implementation(libs.androidx.navigation.compose)
implementation(libs.androidx.lifecycle.viewmodel.compose)
implementation(libs.androidx.datastore.preferences)
implementation(libs.androidx.compose.material.icons.core)
implementation(libs.kotlinx.serialization.json)
testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 4: Verify the project still builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (this only proves dependency resolution + existing code still compiles; nothing new exists yet).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts
git commit -m "build: add navigation, datastore and serialization dependencies"
```

---

### Task 2: Canvas data model and pure reducers

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasState.kt`
- Test: `app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasStateTest.kt`

**Interfaces:**
- Produces: `data class StickerItem(id: String, assetName: String, x: Float, y: Float, scale: Float = 1f, rotationDegrees: Float = 0f, zIndex: Int)`, `data class CanvasState(stickers: List<StickerItem> = emptyList(), nextZIndex: Int = 0)`, and extension functions `CanvasState.withStickerAdded(id, assetName, x, y): CanvasState`, `CanvasState.withStickerRemoved(id): CanvasState`, `CanvasState.withStickerBroughtToFront(id): CanvasState`, `CanvasState.withStickerTransformed(id, x, y, scale, rotationDegrees): CanvasState`. Used by Tasks 3, 4, 5.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasStateTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.atakan.travelplanner.ui.canvas.CanvasStateTest"`
Expected: FAIL to compile — `CanvasState` and friends don't exist yet.

- [ ] **Step 3: Implement the data model and reducers**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasState.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.atakan.travelplanner.ui.canvas.CanvasStateTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasState.kt app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasStateTest.kt
git commit -m "feat: add CanvasState data model and pure reducers"
```

---

### Task 3: JSON serializer for CanvasState

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasStateSerializer.kt`
- Test: `app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasStateSerializerTest.kt`

**Interfaces:**
- Consumes: `CanvasState`, `CanvasState.withStickerAdded`, `CanvasState.withStickerTransformed` (Task 2).
- Produces: `object CanvasStateSerializer { fun serialize(state: CanvasState): String; fun deserialize(raw: String): CanvasState }`. Used by Task 4.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasStateSerializerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.atakan.travelplanner.ui.canvas.CanvasStateSerializerTest"`
Expected: FAIL to compile — `CanvasStateSerializer` doesn't exist yet.

- [ ] **Step 3: Implement the serializer**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasStateSerializer.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.atakan.travelplanner.ui.canvas.CanvasStateSerializerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasStateSerializer.kt app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasStateSerializerTest.kt
git commit -m "feat: add JSON round-trip serializer for CanvasState"
```

---

### Task 4: DataStore-backed repository

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasRepository.kt`

**Interfaces:**
- Consumes: `CanvasState`, `CanvasStateSerializer.serialize`, `CanvasStateSerializer.deserialize` (Tasks 2, 3).
- Produces: `interface CanvasStateStore { val canvasState: Flow<CanvasState>; suspend fun loadOnce(): CanvasState; suspend fun save(state: CanvasState) }` and `class CanvasRepository(context: Context) : CanvasStateStore`. Used by Task 5 (via the interface) and Task 10 (constructs the concrete class).

This task is Android-`Context`-bound I/O wrapped around the already-tested pure serializer from Task 3, so it is verified manually in Task 12 (persistence-across-relaunch check) rather than with a JVM unit test — there is no meaningful additional branching logic here to unit test beyond what Task 3 already covers.

- [ ] **Step 1: Implement the repository**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasRepository.kt`:

```kotlin
package com.atakan.travelplanner.ui.canvas

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface CanvasStateStore {
    val canvasState: Flow<CanvasState>
    suspend fun loadOnce(): CanvasState
    suspend fun save(state: CanvasState)
}

private val Context.canvasDataStore by preferencesDataStore(name = "canvas_state")
private val CANVAS_STATE_KEY = stringPreferencesKey("canvas_state_json")

class CanvasRepository(private val context: Context) : CanvasStateStore {

    override val canvasState: Flow<CanvasState> = context.canvasDataStore.data.map { prefs ->
        CanvasStateSerializer.deserialize(prefs[CANVAS_STATE_KEY] ?: "")
    }

    override suspend fun loadOnce(): CanvasState = canvasState.first()

    override suspend fun save(state: CanvasState) {
        context.canvasDataStore.edit { prefs ->
            prefs[CANVAS_STATE_KEY] = CanvasStateSerializer.serialize(state)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasRepository.kt
git commit -m "feat: add DataStore-backed CanvasRepository"
```

---

### Task 5: CanvasViewModel with debounced persistence

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasViewModel.kt`
- Test: `app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasViewModelTest.kt`

**Interfaces:**
- Consumes: `CanvasStateStore` (Task 4), `CanvasState` reducers (Task 2).
- Produces: `class CanvasViewModel(repository: CanvasStateStore, assetNamesProvider: suspend () -> List<String>, ioDispatcher: CoroutineDispatcher = Dispatchers.IO, idProvider: () -> String = { UUID.randomUUID().toString() }) : ViewModel()` exposing `val canvasState: StateFlow<CanvasState>`, `val assetNames: StateFlow<List<String>>`, `fun onStickerAdded(assetName: String, x: Float, y: Float)`, `fun onStickerTransformed(id: String, x: Float, y: Float, scale: Float, rotationDegrees: Float)`, `fun onStickerBroughtToFront(id: String)`, `fun onStickerRemoved(id: String)`. Used by Task 10.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasViewModelTest.kt`:

```kotlin
package com.atakan.travelplanner.ui.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.atakan.travelplanner.ui.canvas.CanvasViewModelTest"`
Expected: FAIL to compile — `CanvasViewModel` doesn't exist yet.

- [ ] **Step 3: Implement the ViewModel**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasViewModel.kt`:

```kotlin
package com.atakan.travelplanner.ui.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.atakan.travelplanner.ui.canvas.CanvasViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasViewModel.kt app/src/test/java/com/atakan/travelplanner/ui/canvas/CanvasViewModelTest.kt
git commit -m "feat: add CanvasViewModel with debounced persistence"
```

---

### Task 6: Sticker catalog

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerCatalog.kt`

**Interfaces:**
- Produces: `data class StickerAsset(val name: String, @DrawableRes val drawableRes: Int)`, `val STICKER_CATALOG: List<StickerAsset>`, `fun drawableResFor(name: String): Int?`. Used by Tasks 8, 9, 10.

`app/src/main/res/drawable/` already contains 12 vector-drawable stickers (`stamp_1.xml` … `stamp_9.xml`, `stump_10.xml`, `maple_leaf.xml`, `approve_stamp.xml`), imported earlier via Android Studio's SVG-to-vector-asset tool. This task is the one place that maps their stable string keys to their `@DrawableRes` ints (spec §5) — no new drawables are created, no image-loading dependency is needed, and rendering happens with `painterResource` (Tasks 8, 9).

- [ ] **Step 1: Implement the catalog**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerCatalog.kt`:

```kotlin
package com.atakan.travelplanner.ui.canvas

import androidx.annotation.DrawableRes
import com.atakan.travelplanner.R

data class StickerAsset(
    val name: String,
    @DrawableRes val drawableRes: Int
)

val STICKER_CATALOG: List<StickerAsset> = listOf(
    StickerAsset("stamp_1", R.drawable.stamp_1),
    StickerAsset("stamp_2", R.drawable.stamp_2),
    StickerAsset("stamp_3", R.drawable.stamp_3),
    StickerAsset("stamp_4", R.drawable.stamp_4),
    StickerAsset("stamp_5", R.drawable.stamp_5),
    StickerAsset("stamp_6", R.drawable.stamp_6),
    StickerAsset("stamp_7", R.drawable.stamp_7),
    StickerAsset("stamp_8", R.drawable.stamp_8),
    StickerAsset("stamp_9", R.drawable.stamp_9),
    StickerAsset("stump_10", R.drawable.stump_10),
    StickerAsset("maple_leaf", R.drawable.maple_leaf),
    StickerAsset("approve_stamp", R.drawable.approve_stamp)
)

fun drawableResFor(name: String): Int? =
    STICKER_CATALOG.firstOrNull { it.name == name }?.drawableRes
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (This is also the check that every listed drawable name actually resolves — a typo'd `R.drawable.*` reference fails compilation immediately.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerCatalog.kt
git commit -m "feat: add sticker catalog mapping drawable resources"
```

---

### Task 7: Sticker gesture detector

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerGestureDetector.kt`

**Interfaces:**
- Produces: `suspend fun PointerInputScope.detectStickerGestures(onGestureStart: () -> Unit, onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotationDegrees: Float) -> Unit, onGestureEnd: (centroid: Offset) -> Unit)`. Used by Task 8.

Compose Foundation's built-in `detectTransformGestures` does pan+zoom+rotate detection but has no gesture-start/gesture-end hooks, and this feature needs both (bring-to-front on touch-down, trash hit-test on release). This task re-implements that detection loop — using the same public, stable `calculateZoom`/`calculateRotation`/`calculatePan`/`calculateCentroid` utilities the built-in function itself uses — with the two extra hooks added. There is no practical JVM unit test for multi-touch pointer input; correctness is verified through Task 8/10's manual gesture testing.

- [ ] **Step 1: Implement the gesture detector**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerGestureDetector.kt`:

```kotlin
package com.atakan.travelplanner.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.calculateCentroid
import androidx.compose.ui.input.pointer.util.calculateCentroidSize
import androidx.compose.ui.input.pointer.util.calculatePan
import androidx.compose.ui.input.pointer.util.calculateRotation
import androidx.compose.ui.input.pointer.util.calculateZoom
import kotlin.math.PI
import kotlin.math.abs

suspend fun PointerInputScope.detectStickerGestures(
    onGestureStart: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotationDegrees: Float) -> Unit,
    onGestureEnd: (centroid: Offset) -> Unit
) {
    awaitEachGesture {
        var rotation = 0f
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var lastCentroid = Offset.Zero

        awaitFirstDown(requireUnconsumed = false)
        onGestureStart()

        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val rotationChange = event.calculateRotation()
                val panChange = event.calculatePan()
                lastCentroid = event.calculateCentroid(useCurrent = false)

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    rotation += rotationChange
                    pan += panChange

                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val rotationMotion = abs(rotation * PI.toFloat() * centroidSize / 180f)
                    val panMotion = pan.getDistance()

                    if (zoomMotion > touchSlop || rotationMotion > touchSlop || panMotion > touchSlop) {
                        pastTouchSlop = true
                    }
                }

                if (pastTouchSlop) {
                    if (rotationChange != 0f || zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(lastCentroid, panChange, zoomChange, rotationChange)
                    }
                    event.changes.forEach {
                        if (it.positionChanged()) it.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })

        onGestureEnd(lastCentroid)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerGestureDetector.kt
git commit -m "feat: add sticker gesture detector with start/end hooks"
```

---

### Task 8: StickerView composable

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerView.kt`

**Interfaces:**
- Consumes: `StickerItem` (Task 2), `detectStickerGestures` (Task 7), `drawableResFor` (Task 6).
- Produces: `@Composable fun StickerView(item: StickerItem, onGestureStart: (id: String) -> Unit, onDrag: (id: String, centerInWindow: Offset) -> Unit, onGestureEnd: (id: String, x: Float, y: Float, scale: Float, rotationDegrees: Float, finalCenterInWindow: Offset) -> Unit, modifier: Modifier = Modifier)`. Used by Task 10.

Rendering uses `Image` + `painterResource` (Task 6's catalog resolves `item.assetName` to a `@DrawableRes` int) — sticker artwork is a compile-time resource, so there is no decode/cache concern to manage here at all.

- [ ] **Step 1: Implement the composable**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerView.kt`:

```kotlin
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
                val topLeft = coordinates.positionInWindow()
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
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerView.kt
git commit -m "feat: add StickerView composable with local-state gesture handling"
```

---

### Task 9: StickerPickerSheet composable

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerPickerSheet.kt`

**Interfaces:**
- Consumes: `drawableResFor` (Task 6).
- Produces: `@OptIn(ExperimentalMaterial3Api::class) @Composable fun StickerPickerSheet(assetNames: List<String>, onStickerPicked: (assetName: String) -> Unit, onDismiss: () -> Unit)`. Used by Task 10.

- [ ] **Step 1: Implement the composable**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerPickerSheet.kt`:

```kotlin
package com.atakan.travelplanner.ui.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPickerSheet(
    assetNames: List<String>,
    onStickerPicked: (assetName: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(assetNames) { assetName ->
                val drawableRes = drawableResFor(assetName) ?: return@items
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .aspectRatio(1f)
                        .clickable { onStickerPicked(assetName) }
                ) {
                    Image(
                        painter = painterResource(id = drawableRes),
                        contentDescription = assetName,
                        modifier = Modifier.aspectRatio(1f)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/StickerPickerSheet.kt
git commit -m "feat: add StickerPickerSheet grid composable"
```

---

### Task 10: CanvasScreen — wiring everything together

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasScreen.kt`

**Interfaces:**
- Consumes: `CanvasViewModel`, `CanvasRepository` (Tasks 4, 5), `STICKER_CATALOG` (Task 6), `StickerView` (Task 8), `StickerPickerSheet` (Task 9).
- Produces: `@Composable fun CanvasScreen(modifier: Modifier = Modifier)`. Used by Task 11.

- [ ] **Step 1: Implement the screen**

Create `app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasScreen.kt`:

```kotlin
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    var draggingCenter by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        canvasState.stickers.forEach { sticker ->
            key(sticker.id) {
                StickerView(
                    item = sticker,
                    modifier = Modifier.zIndex(sticker.zIndex.toFloat()),
                    onGestureStart = { id -> viewModel.onStickerBroughtToFront(id) },
                    onDrag = { _, centerInWindow -> draggingCenter = centerInWindow },
                    onGestureEnd = { id, x, y, scale, rotationDegrees, finalCenterInWindow ->
                        val bounds = trashBounds
                        if (bounds != null && bounds.contains(finalCenterInWindow)) {
                            viewModel.onStickerRemoved(id)
                        } else {
                            viewModel.onStickerTransformed(id, x, y, scale, rotationDegrees)
                        }
                        draggingCenter = null
                    }
                )
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

        FloatingActionButton(
            onClick = {},
            containerColor = if (draggingCenter != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
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
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/canvas/CanvasScreen.kt
git commit -m "feat: add CanvasScreen wiring stickers, picker sheet, and trash zone"
```

---

### Task 11: HomeScreen and navigation wiring

**Files:**
- Create: `app/src/main/java/com/atakan/travelplanner/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/atakan/travelplanner/MainActivity.kt`

**Interfaces:**
- Consumes: `CanvasScreen` (Task 10).
- Produces: `@Composable fun HomeScreen(onOpenCanvas: () -> Unit, modifier: Modifier = Modifier)`; `MainActivity` now hosts a two-route `NavHost` ("home", "canvas").

- [ ] **Step 1: Implement HomeScreen**

Create `app/src/main/java/com/atakan/travelplanner/ui/home/HomeScreen.kt`:

```kotlin
package com.atakan.travelplanner.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.atakan.travelplanner.ui.theme.TravelplannerTheme

@Composable
fun HomeScreen(onOpenCanvas: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onOpenCanvas) {
            Text("Sticker canvas'ı aç")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    TravelplannerTheme {
        HomeScreen(onOpenCanvas = {})
    }
}
```

- [ ] **Step 2: Replace MainActivity's content with the NavHost**

Replace the full contents of `app/src/main/java/com/atakan/travelplanner/MainActivity.kt` (the old `Greeting`/`GreetingPreview` sample is removed — it is fully superseded by `HomeScreen`):

```kotlin
package com.atakan.travelplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.atakan.travelplanner.ui.canvas.CanvasScreen
import com.atakan.travelplanner.ui.home.HomeScreen
import com.atakan.travelplanner.ui.theme.TravelplannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TravelplannerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("home") {
                            HomeScreen(onOpenCanvas = { navController.navigate("canvas") })
                        }
                        composable("canvas") {
                            CanvasScreen()
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify it builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/atakan/travelplanner/ui/home/HomeScreen.kt app/src/main/java/com/atakan/travelplanner/MainActivity.kt
git commit -m "feat: wire home screen and navigation to the sticker canvas"
```

---

### Task 12: Manual end-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Install and launch on a device or emulator**

Run: `./gradlew :app:installDebug`, then open the app.

- [ ] **Step 2: Walk the golden path**

Tap the home screen button → confirm the canvas screen opens. Tap the top-right `+` → confirm the bottom sheet opens with a 3-column grid showing the 12 stamp/leaf stickers from `res/drawable`. Tap one → confirm it's placed on the canvas and the sheet closes.

- [ ] **Step 3: Verify gestures**

Place 2-3 stickers so they overlap. For each: drag to move, pinch to scale, twist with two fingers to rotate. Confirm the sticker you last touched renders above the others (bring-to-front). Confirm gestures feel smooth (no visible stutter) with a single sticker.

- [ ] **Step 4: Verify delete-to-trash**

Drag a sticker down onto the trash icon at the bottom; confirm the trash icon visually reacts while dragging (color change) and the sticker is removed on release. Drag another sticker around without reaching the trash zone; confirm it is not deleted and keeps its new position/rotation/scale after release.

- [ ] **Step 5: Verify persistence**

Place and arrange several stickers, then fully close the app (remove from recents) and relaunch. Navigate back to the canvas screen and confirm the same stickers reappear in the same positions/scale/rotation.

- [ ] **Step 6: Stress-check for jank**

Add 15-20 stickers to the canvas (reusing assets from the 12-entry catalog is fine) and drag/rotate/scale one of them while the others sit idle. Confirm there's no visible frame drop — this is the scenario the local-state-plus-`graphicsLayer` design in Task 8 and the `key(item.id)` iteration in Task 10 exist to protect. If jank is visible, first check with a profiler whether recomposition (not just redraw) is happening on the idle stickers — that would indicate the `graphicsLayer` lambda closure in Task 8 is accidentally capturing/reading state outside the lambda block.

- [ ] **Step 7: Record the outcome**

If everything above passes, the feature is complete. If something fails, note exactly which step and what was observed before making further changes.
