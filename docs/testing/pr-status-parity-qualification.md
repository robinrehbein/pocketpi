# PR/CI status parity: local qualification, 2026-10-31

The existing PR-status producer publishes on the public Pi event bus. The Remote extension now
forwards that value through local IPC for its currently registered session; the Host accepts it
only from that session's bound peer and reuses the existing mapping, bounds, deduplication,
journal, snapshot and encrypted event delivery. This repairs the missing terminal/native bridge;
the host-owned RPC status-key path remains separate. PR creation does not synthesize CI status.

The extension retains an independent copy of valid producer data for registration/reconnect.
Malformed or foreign-session values do not replace that cache. A null clears the status; a new
session cannot inherit the previous session's PR. Host tests cover stale and replaced peers.

## Evidence

- Merged App `595daae`: 3,320 Android unit tests and 34 Wear unit tests passed, no failures or
  skips. Wear debug assembly passed. No emulator or physical device was used for these checks.
- The new `PullRequestStatusScreenIntegrationTest` passed (one test, Gradle 23 s). It uses the
  real `DefaultRemoteRepository`, navigation and `RemoteScreen` with a deterministic transport:
  a running snapshot, named-failure event and chip-to-Changes navigation, passing/cleared
  events, then explicit reconnect and a restored running snapshot. It checks the PR/checks
  actions are displayed without opening external URLs. This is a separate Robolectric run,
  not an Android client connected to the real-Pi fixture.
- Extension regression: the missing-listener assertion failed before the bridge and passed
  afterwards. The later mutation-after-publish regression failed with the changed producer
  object, then passed after defensive copying (one selected runtime test, 6.20 s).
- Host Remote full regression passed 3,799 tests with 38 skips across 261 passing and seven
  skipped files (694.38 s), using `--maxWorkers 2`. Earlier default-concurrency runs hit the
  two composite Git tests' overall 240/300-second limits; no production deadline or assertion
  was relaxed. The PR scenario completed in 189.19 s in the bounded-concurrency run.
- Host extension type checking passed. Biome checked all 467 Remote files with no errors;
  147 warnings and three informational diagnostics remain.
- Two selected Host PR-status tests passed, including clear/deduplication, malformed values,
  mismatched session IDs and stale/replaced peers.
- The isolated `runtime-test/pr-status-pi-harness/run.ts` ran a real external Pi 1.4.0 process,
  the actual PR-status extension/parser and actual Remote extension. PID/session ownership was
  verified. An encrypted simulated device received a pending-check snapshot, a named failed
  check, passing checks and a cleared status. A fresh post-review run passed the same sequence;
  both reported zero cleanup failures.

The Pi fixture isolates HOME, agent settings, project trust, session files, Host state and relay.
Its wrapper injects controlled gh JSON into the actual producer; it does not emit synthetic status
bus messages. Pi's RPC stdout is drained rather than consumed by the Host, so this test exercises
the repaired bus/IPC path rather than the host-owned RPC status-key path. No neural model, real
gh authentication, GitHub request or owner configuration was used. Temporary state was deleted.

## Remaining gates

Host implementation is saved locally as `ca38943a5`, on merged base `ce44cca47`; no push or
release was performed. The real-Pi fixture does not prove host-managed native-worker launch or TUI interaction, actual GitHub CI, combined Android
navigation, production relay/network behavior, physical Samsung/DeX or an independent Linux host.
The user currently has no physical devices or SSH testhost available. These limits must not be
replaced by the local fixture's passing result.
