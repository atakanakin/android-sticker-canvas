# Sticker Canvas (Instagram Story-style Editor) — Design

## 1. Overview

A new full-screen editor reached from a button on the home screen. The
editor is a free-form canvas covering the entire screen. A `+` button in
the top-right opens a bottom sheet grid of stickers (existing `res/drawable`
vector resources, mapped by a small catalog — see §5); tapping one places
it on the canvas. Placed stickers
can be moved, scaled, and rotated via multi-touch gestures, can overlap
each other, and can be deleted by dragging onto a trash-can drop zone that
appears at the bottom of the screen while dragging. The canvas state is
represented by a plain Kotlin data model and persisted so the same layout
reloads on next launch.

## 2. Research findings (why no 3rd-party sticker lib)

- No mature, actively maintained Compose-native library exists for this.
  The closest match (`Stickr`, github.com/adnanaslamdev/Stickr) has 2
  stars / 4 commits — a single-author experiment, not something to take
  a production dependency on.
- The closest mature library (`PhotoEditor`,
  github.com/burhanrashid52/PhotoEditor, ~4.5k stars) is View/XML-based,
  not Compose, and is a full photo-editing surface (filters, emoji, text,
  drawing, undo/redo) — far more surface than needed here, and bridging
  it into a 100%-Compose app via `AndroidView` interop would add more
  complexity than building directly on Compose's own gesture APIs.
- Jetpack Compose Foundation ships official, stable APIs
  (`Modifier.pointerInput` + `detectTransformGestures`, or the
  higher-level `Modifier.transformable()`) that are purpose-built for
  exactly this pan/zoom/rotate scenario, documented by Google's own
  "Multitouch: panning, zooming, rotating" guide. No extra dependency is
  needed for gesture handling — this keeps technical debt lower than
  adopting either library option above.

## 3. Data model

```kotlin
data class StickerItem(
    val id: String,           // stable UUID, assigned on placement
    val assetName: String,    // stable key into STICKER_CATALOG, e.g. "stamp_1"
    val position: Offset,     // top-left in canvas coordinates (px)
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val zIndex: Int           // paint order; higher draws on top
)

data class CanvasState(
    val stickers: List<StickerItem> = emptyList(),
    val nextZIndex: Int = 0   // monotonically increasing counter
)
```

`zIndex` is a plain counter, never a list reorder — bringing a sticker to
front means bumping its `zIndex` to `nextZIndex++`, not moving it within
the list. This keeps list identity stable across recompositions (see
Performance, §6).

## 4. Persistence

- `CanvasRepository` serializes `CanvasState` to JSON (`kotlinx.serialization`)
  and stores it as a single value in a Preferences DataStore entry.
- Room is intentionally not used: there is exactly one canvas and no
  querying/filtering need. A single serialized blob is simpler and has no
  schema-migration surface. If multiple saved canvases are needed later,
  this repository's interface (`suspend fun save(state)`, `fun observe():
  Flow<CanvasState>`) can be swapped for a Room-backed implementation
  without touching the ViewModel or UI.
- All reads/writes run on `Dispatchers.IO`.
- Writes are triggered only on gesture-end (drag/transform finished, or a
  sticker added/deleted) — never on every intermediate pointer-move frame
  — see §6.

## 5. Sticker rendering & asset picker

- Sticker artwork already exists as Android VectorDrawable XML under
  `app/src/main/res/drawable/` (`stamp_1`…`stamp_9`, `stump_10`,
  `maple_leaf`, `approve_stamp` — 12 resources at design time), produced by
  Android Studio's SVG-to-vector-asset import. Because they're already
  compile-time drawable resources, rendering needs nothing beyond
  `painterResource(id)` — no runtime SVG parsing, no Coil, no extra
  dependency.
- A single file, `StickerCatalog.kt`, maps a stable string key (e.g.
  `"stamp_1"`) to each drawable's `@DrawableRes` int
  (`STICKER_CATALOG: List<StickerAsset>` plus a `drawableResFor(name):
  Int?` lookup). `StickerItem` persists the string key, never the raw
  resource int, since a resource int is not a value we want to guarantee
  stable across rebuilds — the string key is ours to keep stable. Adding a
  new sticker means adding one drawable resource plus one line to this
  catalog; no other file changes.
- The bottom sheet shows the catalog in a
  `LazyVerticalGrid(columns = GridCells.Fixed(3))` inside a Material3
  `ModalBottomSheet`, each cell rendered with `painterResource`.
- Tapping a grid entry appends a new `StickerItem` (centered on screen,
  default scale/rotation, `zIndex = nextZIndex++`) to `CanvasState` and
  dismisses the sheet.

## 6. Gesture handling, layering, and performance

This is the part most likely to jank with many stickers on screen, so it
gets explicit rules:

- **Each `StickerItem` is its own composable**, iterated with an explicit
  `key(item.id) { StickerView(item) }` — not index-based — so adding,
  removing, or reordering one sticker doesn't force recomposition of its
  siblings.
- **Live gesture state is local, not routed through the ViewModel per
  frame.** While a finger is down, `detectTransformGestures` updates a
  local `Animatable`/`mutableStateOf` (position/scale/rotation) owned by
  that sticker's own composable. That local state feeds a
  `Modifier.graphicsLayer { translationX = ...; translationY = ...;
  scaleX = ...; scaleY = ...; rotationZ = ... }` lambda block. Because the
  reads happen inside the `graphicsLayer` lambda, Compose only re-runs the
  draw phase (GPU layer transform) on each frame — **not** a full
  recomposition or relayout of the sticker, and not of the tree at all.
- **Commit to `CanvasViewModel`/`CanvasState` only on gesture end**
  (`onGestureEnd`/`awaitAllPointersUp` boundary). This is the one point
  where the local transient state is written into the `StateFlow`, and
  the one point that triggers a debounced persistence write. Intermediate
  frames during an active drag never touch the ViewModel, StateFlow, or
  DataStore.
- **Bring-to-front on touch start**: on the first pointer-down of a
  gesture, bump that sticker's `zIndex` (see §3) and apply it via
  `Modifier.zIndex(item.zIndex.toFloat())`. This is a cheap Int write, not
  a list mutation.
- **Trash drop-zone hit-testing** happens once, at gesture end, by
  comparing the final centroid position to the trash icon's bounds
  (obtained via `onGloballyPositioned`) — not on every frame. The trash
  icon itself only fades in while `stickers.any { it.isDragging }` is
  true (a local per-sticker flag), avoiding recomposition of the trash
  icon's parent on every pointer move.
- **Persistence dispatcher discipline**: `CanvasViewModel` launches
  persistence writes on `viewModelScope` with `Dispatchers.IO`, debounced
  (e.g. 300ms trailing) so rapid consecutive edits (several stickers
  placed/moved back-to-back) collapse into one disk write rather than one
  per event.

## 7. Navigation

- Home screen gets a button that navigates (via `NavController`) to the
  new `CanvasScreen` route. `CanvasScreen` is empty apart from the `+`
  button on first entry; `CanvasViewModel` loads any previously persisted
  `CanvasState` on init.

## 8. New dependencies

- `androidx.navigation:navigation-compose`
- `androidx.lifecycle:lifecycle-viewmodel-compose`
- `androidx.datastore:datastore-preferences`
- `androidx.compose.material:material-icons-core`
- `org.jetbrains.kotlinx:kotlinx-serialization-json` (+ the Kotlin
  serialization Gradle plugin)

No sticker/gesture library and no image-loading library (Coil or
otherwise) is introduced — gestures are native Compose Foundation (§2),
and sticker artwork is already a compile-time drawable resource (§5).

## 9. Testing

- Unit tests for `CanvasRepository` (serialize/deserialize round-trip)
  and `CanvasViewModel` (add/delete/bring-to-front reducers over
  `CanvasState`), independent of any gesture/UI code.
- Manual verification on-device for gesture feel (drag/scale/rotate,
  overlap, delete-to-trash) and for jank with ~15-20 stickers placed
  simultaneously, since that's the scenario §6's rules are protecting
  against.
