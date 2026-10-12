# PocketPi performance validation

## Implemented optimizations

- Keep up to eight project session lists and eight chat snapshots in process-local LRU caches.
- Preserve the outgoing chat before activation and show a previously loaded chat while its
  authoritative snapshot is being fetched.
- Explicit opens still wait for `sessions.open`: historical sessions may fork and return different
  IDs. Never send mutations against a speculative selection.
- Existing cache invalidation clears the corresponding LRU as well. Unpairing and cache bypass
  must continue to discard host data.
- Load drafts and navigation snapshots concurrently with pairings. Each storage failure remains
  isolated; initialization still waits for drafts before allowing mutations.
- Remember Markdown fence splits, lines and inline styled text in composition. Compile Markdown
  regular expressions once rather than on each line or recomposition. Theme changes invalidate
  styled text through its link-color key.

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

Report median and p95 for start/navigation, frame misses during streaming, and peak heap. No numeric
speedup has been established by the unit tests.

## Follow-up work requiring measurements or a separate design

- Collect physical-device measurements using the setup in [macrobenchmarks.md](macrobenchmarks.md).
  Fixtures cover startup, synthetic Markdown and the full chat/Timeline presentation path, but
  exclude live transport, production repository/cache operations and navigation transitions.
- Baseline profiles and broader workload coverage (questions, subagents and attachments).
- Byte-weighted transcript caching if heap measurements show the entry limit is insufficient.
- Incremental timeline presentation/Markdown parsing if streaming still dominates CPU time.
- Validate [per-session scroll restoration](chat-scroll-restoration.md) on real devices,
  including rotation, reconnect and changing paginated item indexes.
- Request coalescing and selective prefetch after identifying duplicated calls in traces.
- Optimistic send status: preserve mutation IDs, uncertain-delivery handling and retries; never
  introduce a second send path that can duplicate prompts after reconnect.
- Any new host/protocol endpoint must land in `noah-monorepo` first.

Do not enable R8, throttle transport events or add speculative network fan-out merely to claim a
performance improvement without representative correctness and device measurements.
