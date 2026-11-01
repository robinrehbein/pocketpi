# Native Stop and reconnect qualification — 2026-11-01

## Scope

The joined Android workflow is extended with a private opt-in lifecycle mode. After the existing real native Pi read/bash turn, a separate deterministic provider emits a partial assistant response and holds until Pi receives the actual Android Stop. Android requires visibly rendered partial output and enabled Stop before pressing it. The provider's abort signal and Pi agent_end, independent of the request acknowledgment, establish cancellation.

Only after cancellation and idle, the fixture cuts the actual relay socket and blocks upgrades. Android observes offline; allowing reconnect must increase upgrades while retaining the native worker PID, runtime and session. The retained partial transcript must appear once. A distinct follow-up then completes once before the same workflow continues through shell, explicit local commit/push and controlled PR/CI. Exact lifecycle trace shape and Stop count are reasserted at the very end.

The original successful agent trace remains separate and unchanged. Provider timeout completes with a distinct withheld marker and invalidates lifecycle proof; timeout cannot qualify as Stop. The test manually supplies foreground/validated-network signals because NavigationTestActivity does not install the production monitor.

## Evidence

Private lab: `/private/tmp/pocketpi-paged-production.oge2ybh1`.

| Run | Instrumentation result | Runner duration | Source boundary |
| --- | --- | --- | --- |
| `native-lifecycle-phone-first` | OK, one test, 91.017 s | 93.047 s | Before explicit peer-object pin |
| `native-lifecycle-tablet-pinned` | OK, one test, 93.090 s | 95.364 s | Includes explicit peer-object pin |

Both fixture cleanups passed. Each saved manifest identifies 21 prelaunch source snapshots plus two installed APKs verified against the build outputs. Independent review rehashed all 23 saved files. Both final statuses and separate ordered eight-event traces establish one abort, one socket cut, upgrade 1→2, matching PID/session/runtime, one follow-up and one each local commit/push/controlled PR. The original successful read/bash agent trace still has its exact eight events. Final status is captured after test teardown, so its `deviceOnline=false` is not a claim that the chat failed to reconnect during the test.

Each run retains result/final-status JSON, instrumentation/log files, original agent and lifecycle traces, and four screenshots under `-proofs`. Root inspected the phone running screenshot (partial and Stop visible) and tablet reconnected screenshot (partial retained). Tablet 1600×2560/density 240 was restored to 1080×2400/density 420.

The reviewer requested a final lifecycle reassertion after the entire Git workflow; it was included before both actual runs. A later source-only adjustment scopes the explicit peer-object pin to lifecycle mode, preserving the other modes' existing same-process re-registration semantics. Lifecycle mode still follows the same equality branch qualified by the tablet run. The current normal workflow regression `native-lifecycle-legacy-current` passed (instrumentation 82.896 s, runner 84.981 s), cleanup passed. Its 21 source snapshots and two installed APK pins cover the final scoped code, and it retains the original eight-event agent trace without lifecycle metadata.

Root App/test APK builds, extension TypeScript check and changed TypeScript formatting passed. An initial fixture-only TypeScript error from assigning a simple-stream wrapper to the generic stream API was corrected with separately typed wrappers preserving each original option type. No production runtime code changed in this increment. Independent source/artifact review: `/private/tmp/native-lifecycle-review.md`.

## Observed follow-up

The reconnected tablet screenshot shows `Errors (1)` after the intentional abort. Source audit found Host `host/messages.ts` deliberately maps both assistant stop reasons `aborted` and `error` to the same wire `state: error`; Android counts visible error-state bubbles. The controlled provider produced `aborted` with retained partial output, making this a strong explanation. The exact chip contribution was not opened/captured separately in these runs, so attribution remains an inference. A durable correction must distinguish sanitized interruption from genuine failure across live events and restored snapshots while preserving strict legacy parser compatibility. It must not silently treat incomplete output as completed or hide concurrent genuine errors. This is separate from the successful cancellation/reconnect proof.


## Boundaries

This choreography proves Stop during streaming followed by idle transport recovery only when the actual runs pass. It does not prove lost acknowledgments, disconnection during an active turn, Android/Pi/Host process death, production network monitoring, arbitrary real providers, physical Samsung devices or an independent Linux Host. The user confirmed no physical devices or authorized SSH test host currently available. Personal Host and external services remain untouched.
