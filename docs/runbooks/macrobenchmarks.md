# Reproducible performance benchmarks

## Scope

The `benchmarks` Android test module runs AndroidX Macrobenchmark against a separate,
non-debuggable, shell-profileable APK: `de.robinrehbein.pocketpi.benchmark`.

This APK uses the app's current release configuration (including its current **disabled** R8),
but is debug-key signed. It needs no Play upload key or Firebase secrets. All four Firebase
BuildConfig fields are empty. Its application ID gives it separate pairings, drafts, files,
notifications and preferences. It must never be uploaded to Play.

`PerformanceFixtureActivity`, `FullChatFixtureActivity` and `ConnectedFlowActivity` exist only in
`src/benchmark`: no fixture activity or profileable manifest addition is included in normal
debug/release builds. CI checks merged-manifest isolation. The fixture labels live in
`src/benchmark/res` and are not translated.

The benchmark build type also replaces the relay: `RemoteApplication` builds its transport through
`remoteTransport(context, scope)`, which `src/debug` and `src/release` (and the wear module) define
as `SecureRemoteTransport`, and `src/benchmark` as `FakeRelayTransport`. In the benchmark APK the
app's repository therefore talks only to the fake relay. The background and push clients
(`BackgroundRemoteClient` for notification actions, completion and question upgrades) still build
`SecureRemoteTransport`, but without Firebase configuration nothing reaches them. Debug and release
APKs contain no fake relay or fixture; CI checks the debug APK's dex files for the fixture classes.

### Workloads (ten iterations each)

| Test | Workload | Metrics |
| --- | --- | --- |
| `coldStartup` | Real `MainActivity`, cold process, unpaired/empty app data cleared before each iteration | Time to initial display (TTID) |
| `syntheticSessionSwitch` | Six switches between two fixed 180-message Markdown lists | Frame CPU duration and frame overrun percentiles |
| `syntheticMarkdownStreaming` | 120 incremental text updates, requested every 16 ms, in a visible Markdown item | Frame CPU duration and frame overrun percentiles |
| `fullChatScroll` | Real chat screen with 48 turns/193 messages, four gestures toward history and four toward the tail | Frame CPU duration and frame overrun percentiles |
| `fullChatStreamingAutoFollow` | Real Timeline and chat screen; 120 message upserts while following the tail | Frame CPU duration and frame overrun percentiles |
| `fullChatStreamingAfterScrollUp` | Same stream while reading older turns; assert the completed tail stays offscreen | Frame CPU duration and frame overrun percentiles |
| `coldStartToProjects` | Cold process on the fake relay, paired with the fixture Mac: launch, tap the Mac, wait for the projects | TTID (TTFD once the app reports it), `PocketPi.activate` sum |
| `openUncachedChat` | Fresh process on the session list; tap a 48-turn chat never opened in this process, wait for its tail | `PocketPi.activate` (first), `PocketPi.publishTimeline` and `PocketPi.conversationItems` sums, frame timing |
| `openCachedChat` | Same, after opening and leaving that chat once in the process, so the repository's chat cache holds it | Same as `openUncachedChat` |
| `resumeAfterBackground` | Settled on a 48-turn chat; Home, 20 s away, the task brought back to the front; wait until the chat was read again | `ConnectedFlow.resumeToFresh`, the three `PocketPi.*` sums, frame timing |
| `reconnectAfterDrop` | Settled on the same chat; the fake relay fails the socket; wait until the chat was read again | `ConnectedFlow.dropToConnected`, `ConnectedFlow.dropToFresh`, the three `PocketPi.*` sums |
| `streamingThroughRepository` | Settled on the same chat; 120 scripted upserts at 20 per second with an edit tool call midway, while following the tail | Frame CPU duration and overrun percentiles, the three `PocketPi.*` sums and counts |

All tests use `CompilationMode.None()` so compilation policy is explicit and repeatable. This is
not representative of an app already optimized by Play/baseline profiles; keep the same policy for
before/after comparisons. `coldStartup` is first-screen display, **not** connected-data readiness;
`coldStartToProjects` waits for the projects of a paired Mac.

The synthetic tests use the production `MarkdownText` renderer (including tables) in a simple lazy
list. They deliberately exclude transport, repository/timeline projection, full chat navigation,
composer, auto-follow and the multi-session cache. Session-switch frame timing is **not** end-to-end
session-open latency. The 16 ms delay is a workload request, not a guaranteed arrival rate. Do not
interpret these tests as evidence of a speedup for real conversations.

The three `fullChat*` workloads use production `Timeline` event ingestion, `ChatViewModel` state
collection and `RemoteScreen`: conversation projection, touched files/line counts, error and timeline
markers, tool cards, Markdown bubbles, floating composer and header, and automatic following.
The fixed history has 48 user/tool-call/tool-result/assistant turns and one tail marker. Tools
alternate between read and edit; 20 successful edits carry real diff inputs/patches, and four edits
fail. Fixture unit tests verify nonzero line counts, error items and edit/error timeline markers.
Streaming uses 120 consecutive message upserts plus status/checkpoint/completion events; the driver
validates revision continuity before reporting completion. At update 60 the driver pauses until the
harness verifies a separate checkpoint bubble is visible (following) or absent (reading history).
The harness then resumes the stream. A separate final bubble also verifies visible tail following.
After scrolling toward history, the test first verifies the old tail is absent and then verifies
both the checkpoint and completion do not pull the reader back to the new tail. The controlled
checkpoint and automation overhead are included in the trace; this is a frame workload, not an
end-to-end stream-duration benchmark.

Full-chat setup waits two seconds after the initial accessible tail so initial auto-follow and
asynchronous touched-line projection settle; the scroll-away setup waits another second after its
gestures. These fixed setup intervals are outside measurement. Moderate-speed touch gestures use
only the middle of the list bounds to avoid the real floating header/composer, with a fixed 250 ms
settling pause per gesture. Gesture pauses in `fullChatScroll` are inside its measured trace; frame
metrics do not represent overall gesture wall-clock latency. Implicit UiAutomator idle waits are disabled
and restored per test; explicit settling waits remain.

The full-chat driver publishes synthetic repository state, **not** the production repository:
transport/decryption, navigation transitions, disk/cache writes, request handling, push, and latency
until connected content are still excluded (the connected-flow tests below cover most of them).
The fixture includes no active attachment import, pending questions, subagents or real model
configuration. Besides the transport factory, the only normal-app code change is an inert
`conversationList` test tag; resource-ID exposure is enabled only in the benchmark fixtures.

## Connected flows on the fake relay

`ConnectedFlowBenchmarks` measures what the fixtures above leave out: connecting, requests,
repository publication, navigation and the real screens. `ConnectedFlowActivity` hosts the normal
`RemoteApp` with `RemoteApplication`'s own repository and settings, so the process gets the same
`setForeground` and validated-network signals, push wiring (empty in this build) and stores as the
app. Only the transport is a stand-in.

`FakeRelayTransport` (in `src/benchmark`) answers `projects.list`, `sessions.list`, `sessions.open`
and `session.snapshot` from `src/benchmark/assets/connected-flow.json`: one Mac, three projects and
six sessions, two of them with 48 generated turns (user prompt, edit tool call with a diff, tool
result, Markdown answer) plus a tail marker. Snapshots return the newest 100 messages with a cursor
for older pages. Every other request type is refused as `unsupported` and logged once
(`adb logcat -s FakeRelayTransport`); the fixture advertises no capabilities, so the repository
asks for nothing else while connecting and opening chats. Each reply is encoded as text on a
separate "host" thread and parsed on the app's main thread like a relay frame; decryption is the
only part of the receive path that is missing.

Replies wait one sampled round trip; connecting waits four (socket and TLS, hello/challenge,
authentication). The profiles, with a fixed random seed per process:

| Profile | Round trip | Stalls |
| --- | --- | --- |
| `lan` | 30 ms ± 5 ms | none |
| `lte` (default) | 150 ms ± 50 ms | none |
| `bad` | 400 ms ± 100 ms | 2 % of replies held another 5 s |

The fake relay can also stream the fixture's scripted answer and fail the socket with
`failed(true)`, so the repository reconnects. `ConnectedFlowActivity` takes them as the `command`
extra (`stream`, `drop`); the flow scenarios below send them. The stream (the `stream` block of the
fixture) is 120 chunks, 50 ms apart: `session.status` running, 60 upserts growing a first Markdown
answer (headings, lists, links, a fenced Kotlin block and a table, cycled), that answer completed,
an edit tool call and its result, 60 upserts into a second answer, a separate completion marker and
`session.status` idle, all with consecutive revisions. Streamed events skip the latency profile and
arrive at once, while replies wait a round trip, so a `session.snapshot` answered at revision R can
arrive after events R+1 and later. A real relay keeps host frames in order; start a stream only
once the chat's snapshot is in.

Before its first iteration each test clears the app and starts `ConnectedFlowActivity` with the
`prepare` extra. That pass pairs with the fixture Mac through `repository.pair()` (the real pairing
path, with the fake relay approving), opens the cached chat, leaves a draft there and returns to the
projects. The app's encrypted stores then hold a pairing, a draft and a navigation snapshot, as after
a normal visit, and every cold start reads them back. The pass always runs on the `lan` profile and
reports `connected-flow-prepared` only once the navigation snapshot on disk names the cached chat
and holds the refreshed quick chats; the harness fails at once on `connected-flow-failed`. The
prepare pass is not measured.

Over the app, `ConnectedFlowActivity` lays two 1 dp status nodes the harness waits for:
`connected-flow-<macs|projects|sessions|chat>-<settled|busy>` (settled: connected, not loading and
no request left with the fake relay) and, after a chat finished opening, `connected-flow-cache-hit`
or `-miss`, read from the activation's diagnostics outcome. They recompose only when that status
changes.

- `coldStartToProjects` presses Home and lets Macrobenchmark kill the process and drop caches, then
  launches `ConnectedFlowActivity`. Today a cold start shows the Macs, so it taps the fixture Mac;
  once a cold start opens the last Mac's projects directly, the tap is skipped. `PocketPi.activate`
  then covers the restore activation plus the connect and `projects.list` behind the tap.
- `openUncachedChat` and `openCachedChat` start a fresh process per iteration and navigate to the
  session list outside the measurement, then wait until it is settled, so no late reply lands in
  the measured block. The cached variant opens and leaves the chat once first. Each iteration fails
  unless the open reports `connected-flow-cache-miss` (uncached) or `-hit` (cached).
- An uncached open leaves that chat in the navigation snapshot, and the next cold start would
  restore it into the chat cache. `openUncachedChat` therefore runs the prepare pass again before
  every iteration after the first, which stores the cached chat as the last one.
  Both still wait for `sessions.open` and `session.snapshot`; the cached one shows the cached
  messages while they are in flight. `PocketPi.activate` ends when the snapshot is published, so
  the difference between the two shows mostly in frame timing and in when the tail appears, not in
  the activate duration.

The trace sections come from `Diagnostics.kt` (connection diagnostics, PR #78). `PocketPi.activate`
is an async section and needs API 29 or newer, as the benchmark module does.

### Resume, reconnect and streaming

The flow scenarios start each iteration in a fresh process, open the uncached chat (48 turns, no
draft, so no keyboard) from the session list and wait until it is settled. Only then does the
measured block start.

- `resumeAfterBackground` presses Home, waits `pocketpi.resumeAwaySeconds` (20 by default) and
  brings the app's task back with `am start`, as the launcher icon does. The process and the
  activity stay alive, so this is a warm process with a hot activity; ProcessLifecycleOwner reports
  the stop and the return to `RemoteApplication`, which hands them to the repository exactly as on
  a phone. The time away picks the repository's resume tier: below 15 s with a live socket it does
  nothing (and the scenario would time out), up to 60 s it probes the socket with a snapshot and
  reads the lists again in place, beyond 60 s with a quiet socket it reconnects quietly. 20 s
  measures the probe; pass 65 for the reconnect.
- `reconnectAfterDrop` sends `drop`: the fake relay closes the socket with code 1006 and fails it,
  as a dead socket does. The repository keeps the chat on screen as stale, waits its first backoff
  (about one second with jitter) and reconnects.
- `streamingThroughRepository` sends `stream` and waits for the completion marker, which shows only
  while the list follows the tail. Every iteration after the first runs the prepare pass again, so
  the stored navigation snapshot does not hold an earlier iteration's stream.

Commands reach `ConnectedFlowActivity` through `am start --activity-single-top`, which pauses and
resumes the activity once at the start of the measured block. `PocketPi.publishTimelineCount` and
the other `Count` entries say how many times each section ran.

**Fresh.** `FlowFreshness` (in `src/benchmark`) decides when the app has the open screen again.
A mark starts a wait: every return of the process to the foreground after a stop, and every `drop`.
The screen is fresh once the fake relay delivered, after the mark, a reply to each read that shows
it (`projects.list`, `sessions.list` of the open project, the newest `session.snapshot` page of the
open chat) and the repository is connected, not loading and no longer stale. Both resume tiers and
the reconnect read all three again. The wait ends at the reply that completed the set; the
repository applies it in the same main-thread turn or the next one. `ConnectedFlowActivity` shows
the newest mark as `connected-flow-mark-<n>-<fresh|pending>`, and the benchmarks wait for mark n+1
to turn fresh, so a stale node from the previous mark never ends a measurement.

The harness's own async trace sections:

| Section | From | To |
| --- | --- | --- |
| `ConnectedFlow.resumeToFresh` | ProcessLifecycleOwner's `ON_START` after a stop, right after the repository picked its tier | Fresh |
| `ConnectedFlow.dropToConnected` | Just before the socket fails | The next connection is ready and the state says connected |
| `ConnectedFlow.dropToFresh` | Just before the socket fails | Fresh |

They include the fake relay's latency: one round trip per read, four for the handshake. A mark
that never turns fresh (the tier did nothing, or no reconnect happened) fails the iteration after
90 s instead of measuring nothing.

Pass the latency profile as an instrumentation argument:

```sh
ANDROID_SERIAL=<serial> ./gradlew :benchmarks:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.robinrehbein.pocketpi.benchmarks.ConnectedFlowBenchmarks \
  -Pandroid.testInstrumentationRunnerArguments.pocketpi.relayProfile=lan
```

What the fake relay does not model: TLS and encryption cost, the relay's and Mac's real
processing time, frame chunking of large replies, the real host's page size, background and push
paths, and anything behind a capability. Use the Diagnostics report on a real phone for those.

## Comparing before and after

Numbers are comparable only within one device, or one emulator image (system image, API level,
ABI, RAM and host machine), one profile and one `CompilationMode`. Never compare an emulator run
with a phone run, or runs on two emulator images.

1. Pick one device, one profile and the same `CompilationMode` for both runs.
2. Check out the baseline commit, run the scenarios, and archive the output directory (see
   [Results](#results)) together with the SHA.
3. Check out the candidate and run the same command.
4. Compare medians and the spread of `timeToInitialDisplayMs`, `PocketPi.activateSumMs` (cold
   start), `PocketPi.activateFirstMs`, `PocketPi.publishTimelineSumMs`,
   `PocketPi.conversationItemsSumMs` and the frame percentiles (chat opens). Ten iterations are a
   small sample; repeat a run before trusting a difference of a few percent.
5. When the change is about latency rather than CPU, compare under `lte` and `bad` as well: the
   fake relay's delays are part of every activation.

For the flow scenarios:

1. Run all three on the baseline and the candidate with the same arguments:

   ```sh
   ANDROID_SERIAL=<serial> ./gradlew :benchmarks:connectedBenchmarkAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=de.robinrehbein.pocketpi.benchmarks.ConnectedFlowBenchmarks#resumeAfterBackground,de.robinrehbein.pocketpi.benchmarks.ConnectedFlowBenchmarks#reconnectAfterDrop,de.robinrehbein.pocketpi.benchmarks.ConnectedFlowBenchmarks#streamingThroughRepository \
     -Pandroid.testInstrumentationRunnerArguments.pocketpi.relayProfile=lte
   ```

2. Resume: compare `ConnectedFlow.resumeToFreshFirstMs` once with the default 20 s away (probe)
   and once with `-Pandroid.testInstrumentationRunnerArguments.pocketpi.resumeAwaySeconds=65`
   (reconnect). `PocketPi.activateSumMs` is 0 for a probe and covers the restore activation for a
   reconnect, which tells which tier ran.
3. Reconnect: compare `ConnectedFlow.dropToConnectedFirstMs` (backoff and handshake) and
   `ConnectedFlow.dropToFreshFirstMs`; their difference is the time from ready to the chat read
   again, which is what a faster reconnect path should shrink.
4. Streaming: compare the frame percentiles and `frameOverrunMs`, then `PocketPi.publishTimelineSumMs`
   and `PocketPi.conversationItemsSumMs` with their counts: a lower sum at the same count means
   cheaper publishes, a lower count means fewer of them.
5. When the candidate is a rewrite of the resume or reconnect path, also run the commit just before
   it, so the table in the pull request shows before and after on the same image.

A baseline needs the trace sections, so it must be a commit that contains `Diagnostics.kt`. A
baseline for the flow scenarios also needs this harness (`FlowFreshness.kt`): to measure an older
commit, check it out and copy `src/benchmark` and `benchmarks/` from the candidate over it. The
harness compiles against the app's repository, so that works only back to commits whose
`RemoteState` already has `stale`.

## Build and correctness checks

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew :assembleBenchmark :benchmarks:assembleBenchmark
./gradlew :testBenchmarkUnitTest --tests '*FullChatFixtureRepositoryTest' --tests '*FakeRelayTransportTest'
python3 scripts/check-benchmark-isolation.py \
  build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  build/intermediates/merged_manifests/benchmark/processBenchmarkManifest/AndroidManifest.xml
```

Normal PR CI compiles both benchmark APKs and checks isolation, but does not gate performance on
shared-runner/emulator timings. Existing debug instrumentation tests still run in CI.

## Real-device measurement (Robin)

Use a dedicated API 29+ device with USB debugging, a fixed refresh rate, stable temperature and
battery conditions. Avoid background workloads. Connect only the intended test device, or set
`ANDROID_SERIAL`. Do not pair the benchmark app with a host; it cannot reach one anyway, since its
transport is the fake relay.

```sh
ANDROID_SERIAL=<serial> ./gradlew :benchmarks:connectedBenchmarkAndroidTest
```

To run a single workload:

```sh
ANDROID_SERIAL=<serial> ./gradlew :benchmarks:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.robinrehbein.pocketpi.benchmarks.PocketPiBenchmarks#syntheticMarkdownStreaming
```

Record the git SHA, device model, Android version, refresh rate, compilation mode and thermal state
with every result. Run the same scenarios on the baseline and candidate using the same device.
Do not use emulator numbers or debug builds to claim a production improvement.

For **correctness smoke testing only**, an emulator can be used explicitly:

```sh
./gradlew :benchmarks:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

The error suppression is not set in the module's defaults. Do not suppress physical-device warnings
such as low battery or thermal problems for real measurements.

## Results

Gradle pulls JSON and per-iteration Perfetto traces into:

```text
benchmarks/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/
```

Archive the entire directory outside Git for each baseline/candidate. Open `.perfetto-trace` files
in Android Studio or Perfetto. `PocketPi.fixture.streamAppend` marks fixture state updates only;
Compose parsing/layout/drawing happen later and must be inspected separately.

In `*-benchmarkData.json`:

- `metrics.timeToInitialDisplayMs` contains startup median and all ten iteration values;
  `timeToFullDisplayMs` appears once the app reports itself fully drawn.
- `metrics.PocketPi.activateFirstMs` / `SumMs` and the other `<section><Mode>Ms` entries come from
  the connected-flow trace sections; `ConnectedFlow.*FirstMs` come from the flow harness.
- `PocketPi.fullChat.timelineEvent` trace sections cover real Timeline ingestion only; subsequent
  repository publication, conversation projection and Compose work are outside that section.
- `sampledMetrics.frameDurationCpuMs` and `frameOverrunMs` contain P50/P90/P95/P99 and raw frame samples.
- Positive frame overrun indicates a missed frame deadline; CPU frame duration alone is not total
  display latency.
- `context` describes the measurement device/environment.

Do not compare absolute frame durations across devices/refresh rates. Ten startup samples are a
small baseline; collect more repeated runs before drawing conclusions about tail latency.

## Next steps

1. Collect a physical-device baseline, especially on Samsung.
2. Compare the full-chat traces and frame distributions against the Markdown-only workloads to
   identify whether projection, layout, Markdown or auto-follow dominates.
3. Use those traces to prioritize incremental streaming processing and frame-aligned auto-follow;
   retain the full-chat workloads and their follow/no-follow assertions as regression checks.
4. Validate [per-session scroll restoration](chat-scroll-restoration.md) on physical devices.
5. Add Baseline Profile generation and a matching compilation-mode comparison in a later PR.
