# Per-session chat reading positions

PocketPi retains the most recent 16 chat viewports in navigation-owned saveable state. Keys include
host, project and canonical session ID, so another host/session cannot reuse a reading position.
Entries contain only the first visible item ID, a message-relative fallback index, pixel scroll
offset and auto-follow flag. They do not contain message text and do not write to disk independently
of Android's normal saved-instance-state mechanism.

## Behavior

- A new chat follows the latest message, as before.
- A chat left while reading history returns to its saved message/offset after content loading and
  initial header/composer measurement finish. New messages do not force it to the tail.
- While the error filter is active, normal restoration is deferred. Leaving the filter restores the
  normal anchor/follow policy rather than saving indexes from the filtered list.
- A chat left while following goes to its current tail, including messages received while away.
- IDs, not absolute list indexes, locate the reading anchor. Prepended pages and the load-older
  control do not shift a surviving anchor.
- If the saved message is no longer loaded, the message-relative fallback is clamped to available
  content. No automatic history/network request is issued to find a missing anchor.
- Empty/loading and error-filtered layouts do not overwrite the normal reading position.
- Explicit manual scrolling, timeline jumps and the scroll-to-latest action cancel a pending restore.
- Activity recreation restores the bounded viewport map and follow state. A changed screen size or
  message layout can change exact pixel placement; retaining the message is best effort.
- Least recently used entries are evicted after 16 chats. This is not an unlimited persistent history.

The save observer does not rebuild conversation presentation or write every message delta to a
file. It stores changed viewport/follow snapshots on the UI thread. Restoration uses production
`LazyListState`; normal key-based list anchoring still handles prepended pages while a chat is open.

## Validation

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Unit tests cover key isolation, LRU eviction, save/restore, malformed saved values, stable anchors,
changed list prefixes, missing anchors and invalid offsets. New navigation instrumentation tests
cover A → another chat → A with prepended history, activity recreation followed by streaming while
reading history, returning to a following chat with a new tail, recreation with the error filter
active, and scroll-to-latest canceling a pending restore while content is loading. Touch input in
these tests advances the Compose test clock so the intermediate user-scroll state is observed.

CI runs the full debug instrumentation suite. The full-chat benchmark fixture starts fresh and
bypasses navigation; its follow/no-follow workloads remain useful regression smoke tests, but do
not themselves measure the shared navigation viewport store.

## Real-device checks (Robin)

- Switch between several long chats on Samsung; reading positions should remain independent.
- Rotate while reading, resize in split-screen/DeX, and show/hide the keyboard.
- Return during reconnect/loading and after the host truncates or replaces available history.
- Load older messages and toggle the error filter; verify normal reading anchors are not polluted.
- Use timeline markers and scroll-to-latest while loading; explicit actions must win over restore.
- Check an app process recreation with restored navigation. Force-stop is not guaranteed to restore
  Android saved state and is not a persistence contract.

No measured speedup is claimed. This change removes repetitive manual scrolling when revisiting
sessions; device-specific behavior still needs the checks above.
