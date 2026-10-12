# File Browser Tiles and Peek Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the file browser's plain listing with preview tiles, a long-press peek, and the approved floating pill header.

**Architecture:** Keep the existing `session.files.v1` protocol and full file reader. Add a bounded, current-folder preview cache to `ProjectFilesLoader`; render file and folder tiles in Compose; show a popup anchored to the long-pressed tile without changing the browser path. Build the data, tile, and header parts in parallel after a small shared type contract, then integrate them in one branch.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, coroutines, Android unit/Robolectric tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-29-file-browser-tiles-and-peek-design.md` (approved visual reference: `/Users/robinrehbein/.codex/visualizations/2026/09/29/01a0ee46-a229-7f42-938d-c2d112304e51/file-view-pill-header.html`).

## Global Constraints

- Keep `session.files.v1` unchanged; previews use the existing first `session.files.read` page, and folder peek uses the first `session.files.list` page.
- A tap navigates; a long press peeks and must never navigate. Preserve full file reading, quoting, Back behavior, pagination, and phone/tablet support.
- Render only host-returned preview text. Bound cached text to 2,048 characters, the cache to 24 paths in the current folder, and concurrent preview reads to two.
- Use `FloatingSurface` and theme roles, with 48 dp action targets. The left header pill has 8 dp extra start padding, 20 dp end padding, 12 dp between icon target and text, and 0 dp extra title/subtitle spacing.
- Add every new user-facing string to both `src/main/res/values/remote_files.xml` and `src/main/res/values-de/remote_files.xml`.
- Work in a new isolated worktree/branch from `main` when execution begins; the current `ui/refresh-loading-spinner` checkout and its untracked `work/` preview are unrelated. One final PR targets `main`. Agents edit disjoint file sets and do not stage or commit concurrently; the integrator commits each reviewed deliverable.

## Review Focus

1. A late preview or folder-peek reply after navigation, reload, or session change must not appear in the new folder. Task 1 tests this with held responses.
2. A binary, omitted, empty, or failed preview must display its real state and still allow a normal file tap. Tasks 1 and 2 test these cases.
3. Long press must open Peek without triggering the tap action, including TalkBack's semantic long-click. Task 2 tests both callbacks and semantics.
4. A Peek near the top or side of a narrow phone must remain on-screen, positioning below the tile if there is no room above. Task 2 tests the pure position provider at 320 dp width.
5. Nested navigation and tablet inspector must keep usable back, close, reload, and readable header text. Tasks 3 and 4 test these layouts.

---

## Execution order and ownership

1. **Integrator, Task 0:** create the shared preview/peek model contract; commit it.
2. **Parallel agents A, B, C:** A owns loader and repository wiring; B owns tiles, popup, and its strings; C owns the pill header. Their file sets do not overlap. Give each agent this plan, the spec, its task only, and the integration branch path. Each returns changed files, test output, and unresolved issues without staging or committing.
3. **Integrator, Task 4:** review A/B/C, commit each file set, wire the pieces together, run project checks, request an independent whole-diff review, and open one PR. Do not run dependent compilation while an agent is still changing a shared interface.

### Task 0: Shared model contract (integrator, before parallel dispatch)

**Files:**
- Create: `src/main/java/de/joinnoah/pi/remote/FilePreviewModels.kt`

**Interfaces:**
- Produce `FileTilePreview(loading: Boolean = true, content: String? = null, binary: Boolean = false, tooLarge: Boolean = false, failure: FilesFailure? = null)`.
- Produce `FilesPeek(path: String, type: FileEntryType, loading: Boolean = true, listing: FileListing? = null, failure: FilesFailure? = null)`.
- Produce `FILE_TILE_PREVIEW_CHARS = 2048`, `MAX_CACHED_FILE_PREVIEWS = 24`, and `MAX_FILE_PEEK_NAMES = 8` as internal constants.

- [ ] **Step 1: Add the contract exactly as specified.** Keep the models immutable data classes; no protocol parsing changes. This small declaration is exercised by the Task 1 and Task 2 behavior tests rather than a test that repeats its fields.
- [ ] **Step 2: Run `./gradlew compileDebugKotlin`; expect PASS.** Commit only `FilePreviewModels.kt`.

### Task 1: Bounded preview and folder-peek data (agent A, parallel)

**Files:**
- Modify: `src/main/java/de/joinnoah/pi/remote/ProjectFiles.kt`
- Modify: `src/main/java/de/joinnoah/pi/remote/ProjectFilesLoader.kt`
- Modify: `src/main/java/de/joinnoah/pi/remote/RemoteRepository.kt`
- Modify: `src/main/java/de/joinnoah/pi/remote/DefaultRemoteRepository.kt`
- Modify: `src/main/java/de/joinnoah/pi/remote/RemoteViewModels.kt`
- Create: `src/test/java/de/joinnoah/pi/remote/ProjectFilesPreviewTest.kt`

**Interfaces:**
- Consume Task 0 models and constants.
- Extend `FilesState` with `previews: Map<String, FileTilePreview> = emptyMap()` and `peek: FilesPeek? = null`.
- Produce `ProjectFilesLoader.requestPreview(path: String)`, `showPeek(path: String, type: FileEntryType)`, and `dismissPeek()`; expose matching `RemoteRepository` and `ChatViewModel` methods named `requestFilesPreview`, `showFilesPeek`, and `dismissFilesPeek`.
- `path` is relative to the session root; these methods do not modify `FilesState.path`, `listing`, or `file`.

- [ ] **Step 1: Write failing coroutine tests** for two simultaneous preview reads with a third queued, cache cap/character cap, deduped requests, file peek reusing its preview, folder peek listing without navigation, and late replies discarded after close/folder change/reload/session change. Assert binary, too-large, empty, offline, and failure states directly in `FilesState`.
- [ ] **Step 2: Run the new test; expect missing methods/fields.** `./gradlew testDebugUnitTest --tests de.joinnoah.pi.remote.ProjectFilesPreviewTest`
- [ ] **Step 3: Implement preview requests using first-page `FileSource.filesRead(..., 0, null)` and folder Peek using first-page `filesList(..., null)`.** Limit active previews to two, cache current-folder results up to 24 entries and 2,048 characters, and stop/ignore stale jobs on lifecycle changes. Keep an open file Peek's preview in the cache until dismissal. Mark the `FilesPeek` complete when its file preview or folder listing arrives. Do not interfere with the existing `fileJob`/`listJob` for navigation.
- [ ] **Step 4: Add repository and view-model forwarding methods and run the new test to PASS.** Then run `./gradlew testDebugUnitTest --tests de.joinnoah.pi.remote.ProjectFilesLoaderTest` to guard the current reader.
- [ ] **Step 5: Return a file list and test output to the integrator.** Do not stage or commit while agents B and C run.

### Task 2: Standalone tiles and anchored Peek (agent B, parallel)

**Files:**
- Create: `src/main/java/de/joinnoah/pi/remote/FileTiles.kt`
- Create: `src/test/java/de/joinnoah/pi/remote/FileTilesUiTest.kt`
- Modify: `src/main/res/values/remote_files.xml`
- Modify: `src/main/res/values-de/remote_files.xml`

**Interfaces:**
- Consume Task 0 models, `FileEntry`, and `FileEntryType` only; do not edit `FilesPane.kt`.
- Produce `@Composable internal fun FileEntryTile(entry: FileEntry, preview: FileTilePreview?, onOpen: () -> Unit, onRequestPreview: () -> Unit, onPeek: (IntRect) -> Unit, modifier: Modifier = Modifier)`.
- Produce `@Composable internal fun FilePeekPopup(peek: FilesPeek, preview: FileTilePreview?, anchorBounds: IntRect, onDismiss: () -> Unit)` and an internal `FilePeekPositionProvider` whose popup is above the anchor if it fits, otherwise below, with 16 dp horizontal screen margins.

- [ ] **Step 1: Write failing Robolectric Compose tests** for a file tile's faint three-line preview beside its icon and above its name, placeholder states, a folder tile without a default excerpt, disabled symlink/submodule tiles, tap versus semantic long-click callbacks, 48 dp targets, and dismissible Peek. Add a pure position-provider test for anchors near top and both horizontal edges.
- [ ] **Step 2: Run the tile tests; expect missing composables.** `./gradlew testDebugUnitTest --tests de.joinnoah.pi.remote.FileTilesUiTest`
- [ ] **Step 3: Implement the tiles and popup.** Use `combinedClickable`, `LaunchedEffect` for visible file-tile preview requests, `MaterialTheme.colorScheme` surfaces, a clipped/faded monospace excerpt, `PopupProperties(focusable = true)`, and a folder preview with at most eight names and a `more` indication. Add concise EN/DE strings for preview states and accessibility actions. The peek stays read-only and provides no navigation action.
- [ ] **Step 4: Run tile tests and `python3 scripts/i18n-check.py .`; expect PASS.** Return changed files and output without staging or committing.

### Task 3: Floating pill header (agent C, parallel)

**Files:**
- Modify: `src/main/java/de/joinnoah/pi/remote/FilesPane.kt`
- Modify: `src/main/java/de/joinnoah/pi/remote/RemoteScreens.kt`
- Modify: `src/test/java/de/joinnoah/pi/remote/FilesPaneUiTest.kt`

**Interfaces:**
- Change `FilesPane` to accept `projectName: String? = null` after `modifier` so existing direct tests compile; the production call passes `state.project?.optionalText("name")?.takeIf(String::isNotBlank)`.
- This task changes the header only; leave `FolderList` and `FilesActions` intact for Task 4.

- [ ] **Step 1: Write failing header tests** for two separate pill surfaces, root `X` and project subtitle, nested Back plus Close, reload enabled/disabled, and ellipsized long paths on narrow widths.
- [ ] **Step 2: Run `FilesPaneUiTest`; expect header assertions to fail.** `./gradlew testDebugUnitTest --tests de.joinnoah.pi.remote.FilesPaneUiTest`
- [ ] **Step 3: Replace the flat top bar and divider with `FloatingSurface` pills.** Use `CircleShape`, the session header's 16 dp horizontal/8 dp vertical placement, left-pillar 8 dp start/20 dp end padding, a 48 dp icon target followed by 12 dp space, and a two-line title/subtitle column with 0 dp added vertical spacing. Keep the right reload bubble 48 dp. On nested views retain Back and Close in the left pill; ellipsize text before either action is clipped.
- [ ] **Step 4: Run `FilesPaneUiTest`; expect PASS.** Return changed files and output without staging or committing.

### Task 4: Grid integration, full verification, and review (integrator, after A/B/C)

**Files:**
- Modify: `src/main/java/de/joinnoah/pi/remote/FilesPane.kt`
- Modify: `src/main/java/de/joinnoah/pi/remote/ChatActionPill.kt`
- Modify: `src/test/java/de/joinnoah/pi/remote/FilesPaneUiTest.kt`

**Interfaces:**
- Add `FilesActions.onRequestPreview: (String) -> Unit`, `onShowPeek: (String, FileEntryType) -> Unit`, and `onDismissPeek: () -> Unit` and connect them to Task 1 view-model methods.
- In `FolderList`, use one `LazyVerticalGrid`: breadcrumbs, loading/error/notices, section headings, and pagination are full-span items; entries use Task 2 tiles. Preserve `filesList` and `filesEntry:<name>` test tags.

- [ ] **Step 1: Review each parallel result against the spec and contract.** Resolve any interface drift before integration; commit each reviewed agent's disjoint file set separately.
- [ ] **Step 2: Write failing end-to-end pane tests** for grouped tiles, preview request on visible files, tap versus long press, popup dismissal on Back/scroll/navigation, paginated entries, and the tablet inspector. Keep the existing file/line-selection tests.
- [ ] **Step 3: Wire `FileEntryTile` and `FilePeekPopup` into `FolderList` and connect actions through `filesActions`.** Use two columns below 600 dp and three at or above 600 dp. Capture the pressed tile's window bounds; clear its anchor when the path or visible listing changes. Avoid nesting a vertical grid inside `LazyColumn`.
- [ ] **Step 4: Run focused tests to PASS, then the repository checks.** `./gradlew testDebugUnitTest --tests de.joinnoah.pi.remote.FilesPaneUiTest --tests de.joinnoah.pi.remote.FileTilesUiTest --tests de.joinnoah.pi.remote.ProjectFilesPreviewTest`; then `python3 scripts/i18n-check.py .` and `./gradlew testDebugUnitTest assembleDebug lintDebug`.
- [ ] **Step 5: Inspect the diff, get one independent review, fix findings, and repeat affected checks.** Commit integration, push a single branch, open and attach a PR against `main`; wait for green CI and Robin's merge approval. Report the needed Samsung/narrow-phone/tablet device checks without claiming they ran locally.

## Self-review record

- Every approved visual behavior maps to Task 1 (data), Task 2 (tile/peek), Task 3 (header), or Task 4 (integration).
- Agents A/B/C have exclusive file ownership during the parallel phase; Task 4 intentionally edits Task 3's `FilesPane.kt` only after that agent finishes.
- The only shared dependency before dispatch is Task 0's immutable model file. Every Task 1/2/3 test can run without waiting for another parallel agent's code.
