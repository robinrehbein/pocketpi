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
- Resume a chat instead of reading it again. Every first-page `session.snapshot` request ID takes
  one of two forms. The reconnect of the chat on screen, the reselect of a chat kept since it was
  left, a revision gap and the foreground probe send `resume.v1:<epoch>:<revision>:`, with the
  journal epoch of the Timeline's last first page and its last applied revision. A first load, a
  resync after unknown or malformed events, a task refresh, `snapshot.required`, the `stale_cursor`
  recovery, a reply that did not fit and Refresh send the fresh form `resume.v1:-:0:`. A host with a
  phone journal answers a resume with `session.events`, only the events the chat missed: the
  Timeline continues from the previous one (`continueFrom`) with the chat, its older pages and
  cursor, and applies each event with the `after` rule. The repository then runs what live events
  run (list status, titles, receipts, job and Changes hints, but never a notice) and what an
  accepted snapshot runs, and publishes once. While a resume is in flight, the Timeline holds every
  event of its session (`awaitingReply`) and applies it after the reply, exactly once. After a
  failed resume the held events apply at once; when one that arrived meanwhile still waits behind a
  gap, one resume follows, so a host that keeps failing is asked again only as live events come. A
  first page from another epoch (a restarted host, a session reopened in place), or one without an
  epoch while the Timeline holds one, starts the Timeline over even at a lower revision; events that
  arrived for the resume past that page may still count in the old epoch, so they are dropped and
  fetched with one resume from the new page. Tasks the Timeline knew stay current after a replay,
  since task changes emit `snapshot.required`; unknown ones are read with one fresh request. The
  last `DETACHED_TIMELINES` (4) chats left per Mac keep their Timeline and tasks for a reselect. An
  older page that fails with `stale_cursor` takes one fresh first page, which drops the older pages
  on screen, and is asked for once more. That first page holds the session's events as a resume
  does, so a streaming chat cannot make it outdated and its new cursor is always taken. Older hosts
  answer both forms with a snapshot without `epoch`, so no Timeline is continued with them.
- Ask only for the chat on screen. A connection whose capabilities reply names `remote.stream.v1`
  gets `session.focus` once, and again whenever the wanted focus changes: the selected chat in the
  foreground, `[]` on the lists. In the background the chat stays in focus for
  `BACKGROUND_FOCUS_GRACE_MILLIS` (15 s, the quiet-resume window) or while a live run is kept for
  it; then the focus narrows to `[]`. The Mac then leaves out the `message.upsert`s of every other
  session and still sends all other events. A chat that leaves the focus detaches its Timeline,
  which applies nothing meanwhile, so the gaps never ask for a snapshot. A chat switch sends the
  new focus before the chat's first page; a refocus clears the detached flag, holds the session's
  events, and sends the focus and then a resume, so an event that overtakes the reply applies once,
  after it. A Timeline that waits for a reply when its chat leaves the focus cannot detach; its
  focus lapses instead. Events it applies after the reply may carry `after` over upserts the focus
  filtered, so it never resumes from that revision: once the reply is in it detaches, or, back in
  focus, reads a fresh first page with its events held. The grace ends on a timer, not the wall
  clock. The focus is a liveness read that the connection-request cap never holds back, and a new
  connection is legacy (every event) until its own capabilities reply. `BackgroundRemoteClient`
  never sends it.
- Acknowledge what arrived. Once a connection sent `session.focus`, the reader thread counts the
  text of every frame it decrypts and sends `remote.ack` with the highest sequence when
  `ACK_BYTES` (32 KiB) arrived and `ACK_INTERVAL_MILLIS` (200 ms) passed since the last ack, plus
  one trailing ack `ACK_IDLE_MILLIS` (1 s) after the last unacknowledged frame; the first ack
  follows the focus at once. Acks are sealed on the sender thread and never wait for main, so a
  busy UI does not hold back the Mac's delivery window. `remote.ping` goes once per such connection
  and then every `PING_INTERVAL_MILLIS` (30 s) in the foreground; its round trip lands in
  Diagnostics as a request, and a timeout (10 s) ends nothing.
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
- Decode frames off the main thread. OkHttp calls `onMessage` on the socket's reader thread, one
  frame at a time; `SecureRemoteTransport` validates the challenge, creates the `SecureChannel`, and
  decrypts and parses every encrypted frame right there. Each decoded result goes to the
  connection's ordering lane, one consumer on the transport's scope (main in the app) that runs the
  handshake and every listener call in arrival order. `Listener.inbound()` is handed over at
  receipt, before decoding, and stamps the repository's `lastInboundAt`. `onClosed` and
  `onFailure` go through the same lane, so a close or a failure is handled after every frame
  already decoded, even when OkHttp calls them from its writer thread; a frame still being decoded
  then is dropped. `send` only queues the payload: one sender thread seals and sends payloads in
  call order. A seal error ends the connection as broken; a frame OkHttp refuses (the socket is
  closing or failed) is a lost connection that keeps the relay's close code. Handshake frames are
  sealed on the lane, so the pairing acknowledgement leaves before the close that follows it.
  `SecureChannel` locks sending and receiving separately and keeps one `Cipher` and AAD prefix per
  direction, re-initialised with the next sequence's IV per frame. Frames behind a local close or
  an unreadable frame, and payloads queued behind a local close, are dropped without parsing or
  sealing and without a `CAUGHT` entry. The Mac's challenge must arrive within 10 s of the socket
  opening (`CHALLENGE_TIMEOUT_MILLIS`); the whole handshake keeps its 20 s.
  `Wire.decode` compiles its alphabet once and checks the canonical encoding by its padding bits
  instead of re-encoding; `Wire.parse` bounds the size by the text's length and counts UTF-8 bytes
  only between `length` and `length * 3`, and the decrypted payload is not measured again.
  `message.delta` events would need the host (noah-monorepo).
- Publish the chat at most once per `TIMELINE_PUBLISH_MILLIS` (16 ms) while a message streams. The
  Timeline applies every event as it arrives; a `message.upsert` that comes less than 16 ms after
  the last publish does not publish but schedules one trailing publish 16 ms after that publish,
  and further upserts meanwhile ride on it, so a fast stream costs one `RemoteState` update and
  one recomposition per frame. Any other event, a snapshot and a selection change publish at once
  and cancel the trailing publish, and a trailing publish for a Timeline that was replaced is
  dropped. `RemoteRepositoryTest` covers it with a fake clock.
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
before increasing capacity. Network event delivery is not throttled in the app; only the chat's
publish is (above).

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
