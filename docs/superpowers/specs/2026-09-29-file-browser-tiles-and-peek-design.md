# File Browser Tiles and Peek Design

## Goal

Make PocketPi's read-only project file browser feel like the session UI while keeping its existing navigation and file-reading behavior. The approved direction combines the two previews in `file-view-pill-header.html`: a subtle preview inside each text-file tile and a larger peek anchored above the pressed tile.

## Screen and interaction

- Use the app's `FloatingSurface` treatment for the header. The left pill contains close/back, `Files`, and a second line with the project name at the root or the current relative path when nested. The right 48 dp bubble contains reload. Give the left pill 8 dp extra start padding and 20 dp end padding, 12 dp between the 48 dp close/back target and the text, and no extra vertical gap between title and subtitle. Root uses close. Nested folders/files retain both back and close actions within the left pill. Long text ellipsizes without clipping the reload bubble.
- Display the folder listing as a responsive two-column tile grid on phones; use three columns in wider inspector layouts. Preserve the host's order within `Folders` and `Files`. Symlinks and submodules remain visibly disabled with their existing kind labels. Existing loading, empty, unavailable, pagination, retry, and breadcrumb states remain reachable.
- File tiles show a faint, monospace excerpt beside the file icon and above the name. Request the first read page only for tiles that enter the lazy grid's composed viewport; show a quiet placeholder while loading. Keep the excerpt to three lines, fading toward the bottom. Binary, too-large, empty, and failed reads show an appropriate non-content state instead of fake text.
- A tap keeps the existing action: open a folder or a file. A long press on a file or folder opens a read-only peek anchored above its tile when space permits, otherwise below. It never navigates. File peek uses the already fetched first-page text; folder peek lists the first page of child names without changing the current path. Tapping outside, Back, a second navigation, or a scroll dismisses the peek. A visible peek is keyboard and screen-reader dismissible.
- The existing full file view, line selection, quote-to-composer flow, and tablet inspector remain intact.

## Data and bounds

- Use the existing `session.files.read` and `session.files.list` protocol; do not change the host or wire format.
- Keep a current-folder preview cache keyed by relative path, at most 24 entries and 2,048 characters of first-page text per entry. Permit at most two preview reads in flight. Never fetch subsequent pages merely for a tile or peek.
- Cancel or invalidate preview and peek work on browser close, session change, folder navigation, or reload. Ignore late responses from an old path/session. A preview failure must not block opening the file normally.
- Folder peek requests are independent of the navigation listing. Show at most the first eight returned child names with a `more` indication when the page is incomplete.
- Use the project name from `RemoteState.project.name` when available; omit the subtitle at the root if no name is known rather than displaying an opaque ID.

## Accessibility and themes

- Tap and long-press targets are at least 48 dp. Provide a semantic long-click action so TalkBack can open Peek. Icon color is reinforced by file/folder names and kind labels. The popup has an accessible heading and dismiss action.
- Use `MaterialTheme.colorScheme` and `RemoteTypography`; support both app themes. Avoid hard-coded colors or previews of content the host has not returned.

## Acceptance

- The grid and both preview placements match the approved visual direction, including the final left-pill padding and tighter title/subtitle spacing.
- Tests cover file/folder tap versus long press, preview loading and bounds, stale response cancellation, binary/error states, root/nested header actions, and the existing file and tablet flows.
- Run `python3 scripts/i18n-check.py .` and `./gradlew testDebugUnitTest assembleDebug lintDebug`. Manual device check should cover Samsung long press, narrow phones, and the tablet inspector.
