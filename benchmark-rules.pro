# Extra R8 rules for the benchmark build only (it inherits release's minify, shrinking and
# proguard-rules.pro).
#
# R8 still shrinks and optimizes, so macrobenchmarks measure what release ships, but it keeps the
# original names, so Perfetto traces and smoke-test failures stay readable. BaselineProfileGenerator
# does not run on this build: names after R8's merging and inlining would be wrong in a profile.
# It uses the benchmarkProfile build type, which has no R8 at all.
-dontobfuscate

# The fake relay and the connected-flow harness from src/benchmark stay whole classes, so R8
# cannot merge them into their callers and scripts/check-benchmark-isolation.py finds them in the
# benchmark APK. The fixture activities are kept through the benchmark manifest.
-keep class de.joinnoah.pi.remote.FakeRelayTransport
-keep class de.joinnoah.pi.remote.RelayFixture
-keep class de.joinnoah.pi.remote.FlowFreshness
-keep interface de.joinnoah.pi.remote.FlowTrace
