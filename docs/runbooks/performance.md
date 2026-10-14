# PocketPi performance validation

## Implemented optimizations

- Keep up to eight project session lists and eight chat snapshots in process-local LRU caches.
- Preserve the outgoing chat before activation and show a previously loaded chat while its
  authoritative snapshot is being fetched. A Timeline publishes its messages, questions and cursor
  only once its first snapshot is in, and only events of the selected session publish it, so
  events during the fetch never blank the cached chat. A snapshot of the same session keeps the
  older pages it overlaps.
- Selecting the chat on screen again (a reconnect, a resume, a restore) keeps its panes, tool
  output, controls and notice; after the snapshot the panes read again in place, and a failed
  read leaves what they show. Another chat shows the controls and the Changes or Files pane it had
  when it was left (eight chats each, in memory). That pane is on screen from the first frame,
  loading, so it never opens over a composer in use; its reads start after the snapshot, or at
  once when the activation ends without one.
- `connect()` empties every cache when the Mac changes, a cold start included; a restore puts
  what it showed before connecting back, so a failed snapshot keeps the chat on screen.
- Explicit opens still wait for `sessions.open` before they select: historical sessions may fork
  and return different IDs. Never send mutations against a speculative selection. An open of a live
  rpc/tui chat sends `session.snapshot` right behind `sessions.open` without waiting for its reply,
  and takes that snapshot only when the open keeps the ID; a history session's snapshot waits for
  the fork's ID. This relies on the host sending every session's events to the connection, not only
  those of the last `sessions.open`: with open-scoped delivery, events between that snapshot and the
  open would be missed, and a revision gap notices that only when a later event arrives.
  A held snapshot the host refused is asked for once more after the open or the lists, since it was
  read before they confirmed the chat.
- A restore of a chat sends its `session.snapshot` and `sessions.list` together with
  `projects.list`, and the configuration, advisor and commands reads before the snapshot's reply.
  The held snapshot is applied only once the lists confirm the chat (live, not offline), after the
  session list's statuses; otherwise it is dropped. Events of that chat that arrive before its
  Timeline exists wait for it, so none is lost; a revision gap still asks for one resync.
  Diagnostics marks such an activation `snapshot.held`.
- While an open runs, the chat destination shows a read-only preview from the chat cache
  (`peekCachedChat`) with its stored draft, never as the repository's selection: the composer
  takes no edits and every action waits for the selection. A history session's preview says it
  opens as a new copy: `sessions.open` forks it, before anything can be sent.
- Invalidation is per key: creating or forking a session drops only its project's session list,
  closing one also drops its chat. Setting the current cache pointer to null leaves the LRU alone.
  Unpairing, pairing, switching hosts and cache bypass (`clearNavigationCache()`) still discard all
  host data; Refresh in a connected chat only reads the chat again.
- Load drafts and navigation snapshots concurrently with pairings. Each storage failure remains
  isolated; initialization still waits for drafts before allowing mutations.
- Remember Markdown fence splits, lines and inline styled text in composition. Compile Markdown
  regular expressions once rather than on each line or recomposition. Theme changes invalidate
  styled text through its link-color key.
- Keep the composer's text in `ComposerDraft`, not in `RemoteState`: a keystroke recomposes the
  composer only. The text reaches the repository after a 300 ms pause, and at once before a send,
  on stop and when the chat changes. Each write names its chat, and the repository files a late
  one under that chat even when another one is shown. The field takes edits once its chat is
  selected, also while it loads; before that it takes none, so typing never lands on a draft not
  shown yet. Code that reads or changes the draft outside the composer flushes it first or goes
  through it, and `ChatComposerFlushTest` covers each such path. Values handed to message bubbles (such as `MessageFork`)
  stay the same instance while unchanged, so the bubbles skip; `ChatRecompositionTest` checks it.
- Decode frames off the main thread. The handshake still runs on the transport's scope (main in
  the app); once a connection is active, `SecureRemoteTransport` decrypts and parses each frame on
  `Dispatchers.Default.limitedParallelism(1)`, one at a time so frames keep their order, and hands
  the payload back to the scope. `Listener.inbound()` fires at receipt, before decoding, and stamps
  the repository's `lastInboundAt`; a socket close waits for the frames still being decoded.
  `SecureChannel` locks sending and receiving separately, so a send on main never waits for a
  frame being decrypted; frames queued behind a local close or an unreadable frame are dropped
  without parsing and without a `CAUGHT` entry.
  `Wire.decode` compiles its alphabet once and checks the canonical encoding by its padding bits
  instead of re-encoding; `Wire.parse` bounds the size by the text's length and counts UTF-8 bytes
  only between `length` and `length * 3`, and the decrypted payload is not measured again.
  `message.delta` events and upsert coalescing would need the host (noah-monorepo).
- Write the navigation snapshot on structure changes, not per event. A streamed event keeps the
  in-memory caches current and marks the snapshot dirty; a dirty snapshot is written within
  `NAVIGATION_DIRTY_WRITE_MILLIS` (15 s). A selection change, a list read, a chat snapshot, a run
  starting or ending (running ↔ idle/waiting), a status change of another session and going to
  the background write as before (the background at once). A process kill while streaming loses
  at most 15 s of chat tail, which the chat's snapshot replaces on reopen. The stored format stays
  `version` 1: each host is encoded once, and above 4 MB only the trimmed host is encoded again,
  with as many messages dropped at once as their sizes say. One file per host, or loading the
  active host first, would need a format change.
- Present the conversation per message. `Timeline` keeps an ID→index map, so a `message.upsert`
  finds its message without a scan, and replaces only that message's instance. The chat screen's
  `ConversationPresenter` keeps each message's items while the message is the same instance and
  what it reads from the others (its calls' outputs, whether a call shows it, the pending reply,
  being last) is unchanged; a streamed delta rebuilds the items of the message it changed. Only
  the items are incremental: the touched files, error count, rewind targets and subagent cards are
  still a linear pass per delta, but one that sums each message's cached share instead of decoding
  messages again, so a chat with thousands of tool calls still hashes and sorts its touches on Main
  for every delta. The subagent strip is rebuilt only when its cards or the session list change,
  and the scrollbar markers are classified again only from the first changed item on. Unchanged
  values keep their instance, so the chat's composables skip. `ConversationPresenterTest` checks
  random event sequences (upserts, resyncs, older pages and shorter resyncs that drop messages)
  against a full `conversationItems` recompute; if `Timeline` ever copied unchanged messages, the
  presenter would stay correct but rebuild everything again, so keep instances there. The
  `PocketPi.conversationItems` trace section now covers the presenter's pass, so traces from
  before and after this change measure different code under the same name.
- Count the touched files' lines per chat: `TouchedLineCache` keeps each edit's added and removed
  lines by item ID and diffs only edits that are new or changed, however many the chat holds. The
  count runs on `Dispatchers.Default`, checks for cancellation before each item and yields after
  each diff, and while pi streams it follows the conversation at most once a second. The edit
  cards keep their own small diff cache.
- One OkHttp client per process (`baseHttpClient`): the foreground and background relay sockets
  and the artifact CDN derive theirs with `newBuilder()`, sharing its dispatcher, connection pool
  and threads, each with its own ping interval, timeouts and cache.

Caches bound entry count, not total transcript bytes. Check memory with unusually large tool outputs
before increasing capacity. The active timeline and network event delivery are not throttled.

## Local checks

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
```

CI must additionally run `./gradlew pixel2Api36DebugAndroidTest` on its KVM-capable runner.

## Real-device validation (Robin)

Use the same device, host, relay and network for before/after runs. Debug timing is not a release
benchmark. Use a release-like/profileable build for measurements; do not change Play targets.

1. Cold start after force-stop: record launch to first usable list and to current data separately.
2. Open A, open B, then return to A with a large transcript. Repeat at least ten times. Cached content
   should remain visible while the snapshot request is pending.
3. Repeat under slow Wi-Fi, disconnect/reconnect, and switch hosts. Check offline indicators and
   ensure cached text is never interpreted as an acknowledged mutation.
4. Open a historical session and cancel an in-flight open. Confirm canonical fork IDs and cancellation
   semantics are unchanged.
5. Stream a long Markdown response while typing and scrolling on Samsung. Use Android Studio System
   Trace/Perfetto to inspect main-thread work and frame misses. Test theme changes and link clicks.
6. Inspect heap usage after visiting more than eight large chats; verify old entries are evicted.
7. Check drafts, attachment cleanup, notifications, push registration and reconnect after cold start.

8. Stream for 20 minutes in the foreground and 20 in the background: compare battery drain and
   device temperature with the previous release, and check for jank on a Samsung mid-range phone.
   `PocketPi.nav.save` trace sections should appear at most every 15 s while a chat streams.

Report median and p95 for start/navigation, frame misses during streaming, and peak heap. No numeric
speedup has been established by the unit tests.

## Follow-up work requiring measurements or a separate design

- Collect physical-device measurements using the setup in [macrobenchmarks.md](macrobenchmarks.md).
  Fixtures cover startup, synthetic Markdown and the full chat/Timeline presentation path, but
  exclude live transport, production repository/cache operations and navigation transitions.
- Baseline profiles and broader workload coverage (questions, subagents and attachments).
- Byte-weighted transcript caching if heap measurements show the entry limit is insufficient.
- Incremental Markdown parsing of the streaming message if it still dominates CPU time.
- Validate [per-session scroll restoration](chat-scroll-restoration.md) on real devices,
  including rotation, reconnect and changing paginated item indexes.
- Request coalescing and selective prefetch after identifying duplicated calls in traces.
- Optimistic send status: preserve mutation IDs, uncertain-delivery handling and retries; never
  introduce a second send path that can duplicate prompts after reconnect.
- Any new host/protocol endpoint must land in `noah-monorepo` first.

Do not enable R8, throttle transport events or add speculative network fan-out merely to claim a
performance improvement without representative correctness and device measurements.
