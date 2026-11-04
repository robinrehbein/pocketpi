# Active native turn reconnect qualification — 2026-11-01

## Product correction

A full native-session snapshot now retains the current, observed assistant streaming message when public persisted history has not saved it yet. This fixes disappearance of an in-flight partial after reconnect. The Host keeps one bounded current-native-assistant candidate, not arbitrary streaming history. Actual terminal events/history, idle/offline/new turn, history invalidation, registration and closure retire it. Failed Stop requests and active running/waiting transitions preserve the still-running candidate.

Snapshot paging captures and revalidates Live identity, peer, registration epoch, stream epoch, turn epoch and history generation after asynchronous reads. A stale source is rejected rather than mixed across pages. Current updates override earlier candidates; authoritative terminal history wins and retires the candidate so later snapshots cannot resurrect it. Authorization, journal revision allocation, public Pi APIs and single-writer ownership remain in place. No Pi core patch or second session writer is used.

## RED and local checks

Actual old-product `active-cut-phone-first` failed after 75.764 s at the 60-second visible-prefix wait. The same native worker was still held with three provider events, one prompt and zero aborts. Recovery first returned an empty journal replay, then a full snapshot containing seven persisted messages without the held assistant prefix. Fixture cleanup passed.

A focused Host test reproduced the missing pre-read partial (`/private/tmp/pocketpi-active-cut-snapshot-red2.log`, one failure, three protective cases passed). Further regression-first tests reproduced prefix loss after a failed Stop and waiting/repeated-running status (`/private/tmp/pocketpi-active-cut-lifecycle-red.log`, two failures). These issues were corrected before the final checks. An initial test-name filter missed the principal test and passed one protective test; it is not counted as RED proof.

- Full Sessions suite plus Resume/Journal checks: 195 passed, five existing skips across three files (`/private/tmp/pocketpi-active-cut-snapshot-green.log`). Coverage includes terminal precedence and subsequent non-resurrection, updates during paging, history changes, prior idle/new turn, failed Stop, status transitions, actual same-peer/new-peer registration during delayed reads. Registration tests establish rejection, without claiming an exact HostError code.
- Whole extension TypeScript check passed (`/private/tmp/pocketpi-active-cut-final-types.log`), changed TypeScript formatting and diff checks passed.
- Android app/test APK build passed (`/private/tmp/pocketpi-active-cut-build.log`). This increment changes Android instrumentation only; the production app remains the previously qualified interrupted-outcome build. Its 3,475 app/34 Wear quick-check was not repeated because no Android production source changed.

## Actual transport evidence

Private lab `/private/tmp/pocketpi-paged-production.oge2ybh1`. Actual Pi 1.4 native worker and encrypted Host transport, production Android navigation/composer/Stop. The provider emits visible partial output and holds. The fixture closes actual proxy sockets and blocks upgrades while the provider remains held; Android independently observes disconnected state. One-shot guarded allow restores the connection while the same turn remains running. Android requires exactly one visible partial with the same sourceId and enabled Stop before using real Stop. Native cancellation, neutral interrupted/zero-error UI, one follow-up and the complete existing file/agent/shell/local commit/push/controlled PR/CI chain follow.

| Run | Instrumentation | Runner | Cleanup |
| --- | --- | --- | --- |
| `active-cut-phone-fixed` | OK one test, 87.423 s | 89.404 s | Passed |
| `active-cut-tablet-fixed` | OK one test, 90.143 s | 92.265 s | Passed |

Tablet display was 1600×2560/density 240 and restored to 1080×2400/density 420. The unchanged idle-mode regression `active-cut-idle-regression` passed (instrumentation 88.52 s, runner 90.459 s), cleanup passed. It uses the final source/APK inputs and retains the existing stop-before-cut flow.

Each runtime manifest captures 28 relevant source snapshots and two installed APKs compared against build artifacts. Final status retains held-at-cut/offline/reconnected evidence, exact prompt/abort counts and successful snapshot/replay result metadata without transcript text. The ordered original agent and separate lifecycle traces establish current worker PID/session/runtime. Five screenshots for each active-cut phone/tablet run cover running, offline, active recovery, stopped and final success. No matching screenshot directory is claimed for the separate idle regression. Root inspected both active-reconnected screenshots. Source inputs, rather than the dirty base commit alone, identify the actual code.

Both passing active-cut runs received an empty replay followed by a full snapshot containing the held streaming prefix. This qualifies the observed full-snapshot recovery, not merely retained client-side replay. Final recoveryOverflow is false; server response evidence is separately backed by the visible/same-sourceId Android assertions.

## Boundaries

The test activity supplies explicit foreground/network signals; production MainActivity monitoring is not qualified here. This is an actual active-turn socket disconnect/reconnect followed by Stop, with a deterministic local provider. It does not qualify lost command acknowledgment, Android/Pi/Host process death, arbitrary providers or networks, external Plane/GitHub/CI, physical Galaxy devices or an independent Linux host. Devices and an authorized SSH test host are currently unavailable per the user; no repeat request is needed. Personal Host/live infrastructure were untouched. The complete remote-development goal remains active.

Independent final review verified both active-cut runs and the idle regression, their 28 source and two APK hashes per run, owner/trace/workflow evidence and recorded recovery kinds. No remaining concrete blocker was found; see `/private/tmp/active-cut-review.md`. APK compilation provenance is bounded to the local build and installed-artifact comparisons, not independent build attestation.
