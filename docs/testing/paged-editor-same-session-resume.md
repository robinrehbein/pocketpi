# Android continuation after replacing a Pi owner in the same session

## Scope

This qualification resumes the exact saved file and session ID after the old
fixture-owned Pi process has exited. It uses the supported Native phone-worker
launch or RPC in-place resume, with a new real process and IPC peer. It does not
prove concurrent writers, replacement while the old process remains alive, or
that an old question survives owner replacement. Fresh work uses a new question,
grant, nonce and value; Native also requires a new runtime and editor lease.
Public paged-editor and positive-outcome capabilities remain off. The Android
test explicitly disables positive-outcome negotiation, so it does not prove its
owner-incarnation receipt contract.

The original upload reaches Android's possible-write boundary. The fixture holds
the final genuine Source chunk ACK after the original handler accepted it, ends
only its owned old process, observes exit/detach/invalid capture, and resumes the
same saved session before returning that unchanged ACK. The old request must
fail without a Source commit or old SDK result. Its exact 1-MiB uncertain attempt
and draft remain in the encrypted journal. This is intentionally conservative
uncertainty, not an assertion that a negative reply settles every possible write.

Android continues through the same selected chat, without `sessions.open`, a
fork, injected state or manual refresh. A fresh explicit UI command opens a new
editor; a distinct 32-byte value must receive the genuine Source receipt and one
SDK result from the new process. The old journal remains unchanged during a
three-second no-replay observation and is visible through local recovery UI.
Old cleanup is captured before the 50-second reservation-expiry bound; the later
user workflow has no artificial 50-second deadline and Host time keeps running.

## Reproduced defect and correction

`paged-resume-native-01` failed new-owner registration because the fixture's local
Native launch omitted the normal phone-worker extension arguments. It now uses
the supported `phoneWorker` launch flag; this was a fixture correction.

`paged-resume-native-02` reached a verified new owner but the fresh Send button
was disabled. `paged-resume-native-03` waited for real readiness and still failed.
`paged-resume-native-diagnostic-04` captured the cause: Android was connected,
not loading or sending, had a 36-character draft and no open questions, but its
status remained `offline`. Its bounded transport observations contain the old
epoch's initial idle snapshot, waiting and offline events, with no replacement
status or new snapshot. All failed cases completed their owned cleanup.

The Host replaced its Live record but did not tell an already attached phone
about the new incarnation. It now marks replacement registrations and announces
the current status/epoch only after the successful bridge registration reply.
The announcement requires the current Live and peer and is consumed once.
Native replacements mark the new record when a previous Live exists; RPC
in-place starts mark their new record before registration. Wrong or retired peers
cannot consume the pending announcement. Android's existing epoch logic then
requests an authoritative fresh snapshot automatically.

Four focused Host tests observed three failures before implementation and now
pass. They cover idle/running replacement, no announcement before readiness,
wrong/stale peer, new snapshot/journal epoch, duplicate readiness, unchanged
same-process reconnect and refused registration. They use controlled peer/PID
fixtures; actual process evidence is separate below. Forty related Native/RPC
owner/parity tests pass. Daemon/replay/reload/tree checks pass 53 cases, with two
existing opt-in actual-Pi cases skipped because `PI_REMOTE_TEST_PI` was unset.
TypeScript and scoped formatting checks pass. Independent scoped review found
no concrete ordering or stale-peer issue; actual RPC registration remained a
required runtime check.

`paged-resume-native-05` confirmed automatic continuation and one genuine fresh
Source receipt, then failed a recovery UI selector that used only the now-shared
session ID. The test now identifies the original draft by its question ID too;
this selector correction does not relax the retained-text checks.

## Final runtime evidence

| Case | Result | Duration | Old cleanup / observation |
| --- | --- | --- | --- |
| `paged-resume-native-06` | Passed, cleanup passed | 34.668 s | 5,800 / 9,574 ms |
| `paged-resume-rpc-01` | Passed, cleanup passed | 35.040 s | 5,322 / 9,135 ms |

Both final runs use the same app APK
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`
and test APK
`dadddbfd708bbc56d2908208fe0a82548536b5b5370d7d19489b9c2429689b35`.
Each preserves 49 core/test/resource source snapshots. The bounded wire summaries
prove a replacement idle event with a distinct epoch, a correlated automatic
snapshot request/result carrying that epoch and an explicit fresh command after
that snapshot. The independent verifier also checks exactly one total SDK result
with the new nonce/session/PID/mode/value hash and no old-nonce result in any
session. It reports zero errors for both cases.

The final Android debug/test assembly passes. Android production code is unchanged
in this increment and its APK hash matches the preceding export-IO qualification;
that checkpoint's full 3,653 app plus 34 Wear unit result is retained as prior
evidence, not claimed as a new full run. Final Host type/format checks pass; the
four registration regressions were rerun after a type-only assertion correction.

Artifacts live under `/private/tmp/pocketpi-paged-production.oge2ybh1`.
`paged-resume-final-independent-audit.json` records the offline verdicts and
`paged-resume-final-process-exit-audit.json` separately confirms all six owned
Host/Pi PIDs and both Android PIDs absent, with the owned emulator idle. Raw
session headers and the new Source receipt are observed by pinned fixture code
rather than separately preserved. The wire summaries are bounded, not a complete
continuous event log; old RPC question correlation also relies on the pinned
fixture. These limits remain explicit.

The private root controller
pins core sources, APKs and runners, verifies the exact owned emulator and
installed package hashes, preserves trace/proof data and cleans up only its own
processes, app data and nonce-qualified fixture files. Offline audit cannot prove
live process exit; that is checked separately.

## Remaining boundaries

Same-ID replacement after process exit is distinct from a live peer/lease-only
revocation or replacement while the original Pi remains alive. Positive-outcome
incarnation, real Samsung/DeX, independent authorized Linux, broader IME/resource
and production-relay acceptance remain separate. No Wear emulator, iPhone,
external push, release or production-host change is included.
