# Reproducible performance benchmarks

## Scope

The `benchmarks` Android test module runs AndroidX Macrobenchmark against a separate,
non-debuggable, shell-profileable APK: `de.robinrehbein.pocketpi.benchmark`.

This APK uses the app's current release configuration (including its current **disabled** R8),
but is debug-key signed. It needs no Play upload key or Firebase secrets. All four Firebase
BuildConfig fields are empty. Its application ID gives it separate pairings, drafts, files,
notifications and preferences. It must never be uploaded to Play.

`PerformanceFixtureActivity` exists only in `src/benchmark`: no fixture activity or profileable
manifest addition is included in normal debug/release builds. CI checks merged-manifest isolation.
The fixture labels have English and German resources.

### Workloads (ten iterations each)

| Test | Workload | Metrics |
| --- | --- | --- |
| `coldStartup` | Real `MainActivity`, cold process, unpaired/empty app data cleared before each iteration | Time to initial display (TTID) |
| `syntheticSessionSwitch` | Six switches between two fixed 180-message Markdown lists | Frame CPU duration and frame overrun percentiles |
| `syntheticMarkdownStreaming` | 120 incremental text updates, requested every 16 ms, in a visible Markdown item | Frame CPU duration and frame overrun percentiles |

All tests use `CompilationMode.None()` so compilation policy is explicit and repeatable. This is
not representative of an app already optimized by Play/baseline profiles; keep the same policy for
before/after comparisons. Cold startup is first-screen display, **not** connected-data readiness.

The synthetic tests use the production `MarkdownText` renderer (including tables) in a simple lazy
list. They deliberately exclude transport, repository/timeline projection, full chat navigation,
composer, auto-follow and the multi-session cache. Session-switch frame timing is **not** end-to-end
session-open latency. The 16 ms delay is a workload request, not a guaranteed arrival rate. Do not
interpret these tests as evidence of a speedup for real conversations.

## Build and correctness checks

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew :assembleBenchmark :benchmarks:assembleBenchmark
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
- `sampledMetrics.frameDurationCpuMs` and `frameOverrunMs` contain P50/P90/P95/P99 and raw frame samples.
- Positive frame overrun indicates a missed frame deadline; CPU frame duration alone is not total
  display latency.
- `context` describes the measurement device/environment.

Do not compare absolute frame durations across devices/refresh rates. Ten startup samples are a
small baseline; collect more repeated runs before drawing conclusions about tail latency.

## Next steps

1. Collect a physical-device baseline, especially on Samsung.
2. Add a relay-free fixture for the **full** chat presentation/timeline path before optimizing its
   incremental projection; the current Markdown-only fixture cannot catch timeline regressions.
3. Use those traces to prioritize incremental streaming processing and frame-aligned auto-follow.
4. Add per-session scroll restoration as a separate correctness-focused PR.
5. Add Baseline Profile generation and a matching compilation-mode comparison in a later PR.
