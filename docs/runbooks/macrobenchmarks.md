# Reproducible performance benchmarks

## Scope

The `benchmarks` Android test module runs AndroidX Macrobenchmark against a separate,
non-debuggable, shell-profileable APK: `de.robinrehbein.pocketpi.benchmark`.

This APK uses the app's current release configuration (including its current **disabled** R8),
but is debug-key signed. It needs no Play upload key or Firebase secrets. All four Firebase
BuildConfig fields are empty. Its application ID gives it separate pairings, drafts, files,
notifications and preferences. It must never be uploaded to Play.

`PerformanceFixtureActivity` and `FullChatFixtureActivity` exist only in `src/benchmark`: no fixture activity or profileable
manifest addition is included in normal debug/release builds. CI checks merged-manifest isolation.
The fixture labels have English and German resources.

### Workloads (ten iterations each)

| Test | Workload | Metrics |
| --- | --- | --- |
| `coldStartup` | Real `MainActivity`, cold process, unpaired/empty app data cleared before each iteration | Time to initial display (TTID) |
| `syntheticSessionSwitch` | Six switches between two fixed 180-message Markdown lists | Frame CPU duration and frame overrun percentiles |
| `syntheticMarkdownStreaming` | 120 incremental text updates, requested every 16 ms, in a visible Markdown item | Frame CPU duration and frame overrun percentiles |
| `fullChatScroll` | Real chat screen with 48 turns/193 messages, four gestures toward history and four toward the tail | Frame CPU duration and frame overrun percentiles |
| `fullChatStreamingAutoFollow` | Real Timeline and chat screen; 120 message upserts while following the tail | Frame CPU duration and frame overrun percentiles |
| `fullChatStreamingAfterScrollUp` | Same stream while reading older turns; assert the completed tail stays offscreen | Frame CPU duration and frame overrun percentiles |

All tests use `CompilationMode.None()` so compilation policy is explicit and repeatable. This is
not representative of an app already optimized by Play/baseline profiles; keep the same policy for
before/after comparisons. Cold startup is first-screen display, **not** connected-data readiness.

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
until connected content are still excluded. The fixture includes no active attachment import,
pending questions, subagents or real model configuration. The only normal-app code change is an
inert `conversationList` test tag; resource-ID exposure is enabled only in the benchmark fixture.

## Build and correctness checks

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew :assembleBenchmark :benchmarks:assembleBenchmark
./gradlew :testBenchmarkUnitTest --tests '*FullChatFixtureRepositoryTest'
python3 scripts/check-benchmark-isolation.py \
  build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  build/intermediates/merged_manifests/benchmark/processBenchmarkManifest/AndroidManifest.xml
```

Normal PR CI compiles both benchmark APKs and checks isolation, but does not gate performance on
shared-runner/emulator timings. Existing debug instrumentation tests still run in CI.

## Real-device measurement (Robin)

Use a dedicated API 29+ device with USB debugging, a fixed refresh rate, stable temperature and
battery conditions. Avoid background workloads. Connect only the intended test device, or set
`ANDROID_SERIAL`. Do not pair the benchmark app with a host.

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

- `metrics.timeToInitialDisplayMs` contains startup median and all ten iteration values.
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
