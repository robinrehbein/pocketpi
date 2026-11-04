# Remote push qualification

## Scope

The push flow prepares a host-bound branch, HEAD and sanitized origin target, asks for explicit confirmation, persists an intent before dispatch, and never automatically retries a mutation. An unconfirmed outcome remains visible until explicitly acknowledged. The separate remote-status command reports the current target ref; matching the intended HEAD does not establish which client performed the push.

The Android harness uses the real secure transport, repository, Changes entry and Push review/controller with a synthetic idle local peer. Its disposable Mac fixture owns a loopback HTTPS smart-Git remote, private CA and isolated configuration. It does not qualify an actual Pi worker, full production navigation, an independent Linux host or Samsung hardware. No production remote is mutated.

## Evidence recorded on 2026-10-31

- The isolated HTTPS transport exercised actual creation, already-equal state, non-fast-forward rejection, trusted private CA/header configuration, rewrite isolation, same-origin redirect and a Unicode branch. Source HEAD, index and staging remained unchanged.
- Full remote tests before the final delivery/budget/scan corrections: 3,512 passed, 35 skipped. Full Android unit tests at that checkpoint: 3,160 passed. These totals do not qualify subsequent corrections by themselves.
- A focused encrypted host regression completed actual Commit and controlled Push effects, replaced the session source before delivery, and verified `outcome_unknown` rather than a safe refusal. The device must retain recovery state after such a response.
- A diagnostic Android run observed an actual successful push response 57,972 ms after dispatch. The former generic 30-second app timeout had already returned Unknown. This run is deliberately recorded as failed, despite the remote effect.
- Push and status now have separate 150-second and 90-second app budgets, matching the host's longer operation budgets. All 52 GitChanges repository tests pass, including virtual-time late replies and timeout without retry.
- Focused authority regressions verify one complete scan per capture and reject HEAD, origin, policy, source, expiry and actor changes injected during the scan. Local filesystem changes remain subject to sequential checks rather than an atomic lock against other local processes.

- The corrected English phone run passed in 60.739 seconds: real encrypted Android request, actual HTTPS remote mutation, visible confirmation/success, exactly one dispatch on App and Host, unchanged source HEAD/index/staging/worktree. Screenshots were inspected.

- The corrected English phone lost-reply run passed in 37.881 seconds. The fixture disconnected its own secure device socket after actual remote success; the App retained Unknown, reconnected and explicitly requested status. The remote matched, the read count increased, both dispatch counts stayed at one, and source HEAD/index/staging/worktree remained unchanged. The pane harness supplies a synthetic available-network signal; this proves socket-loss recovery, not Android network switching.

## Merged-host adaptive layout runs

The integration of upstream `48663a19a` introduced an asynchronous outbox. Targeted regressions cover unknown Git outcomes at late delivery/overflow, per-entry original-frame completion, watch-result ordering and frame backpressure. The following actual encrypted Android runs also passed against that integrated Host:

| Layout | Language | Scenario | Result |
| --- | --- | --- | --- |
| Phone 1080×2424, 420 dpi | German | Confirmed actual HTTPS Push | Passed, 39.153 s |
| Tablet 2560×1600, 320 dpi | German | Actual Push, lost reply, reconnect, explicit status | Passed, 33.919 s |
| Tablet 2560×1600, 320 dpi | English | Confirmed actual HTTPS Push | Passed, 35.121 s |

Screenshots for target, full HEAD, confirmation, success and recovery were retained outside the repository and inspected. These are emulator configurations, not physical-device qualifications. The English phone runs above were recorded before this upstream integration.

## Required runtime gates

The corrected encrypted Android push and intentional lost-reply/reconnect/status-query scenarios passed for the English phone fixture. Phone/tablet English/German screenshots have been inspected in the recorded emulator scenarios; this is not a complete cross-product of every failure mode and layout. Physical Galaxy S25/Tab S11, hardware input/DeX, independent Linux/devcontainers and actual Pi session qualification remain separate gates.

## Reproduce the isolated fixture

Use the host fixture documented in `packages/pi-atlas/extensions/remote/runtime-test/git-android-e2e/README.md` in the companion monorepo. Opt in with `--with-push`; use a fresh fixture with `--drop-push-reply` for the recovery scenario. Run `GitPushTransportEndToEndTest` with `gitFixturePath`, `gitPushFixtureExpected=true` and the matching `gitPushDropExpected` mode. Keep fixture credentials outside the repository and remove them after use.


## Final merged Host checks

Host integration commit `31bc67b9e` incorporates upstream `48663a19a`; test-only follow-up
`cdf45b590` makes the pending-preview-capacity test independent of child-process scheduling.
The final full Remote run passed 3,668 tests with 35 skips across 255 passing and seven skipped
files. Relay tests passed 70/70 and Relay checks passed. Extension type checking passed on the
final source. The earlier full-run failure was a missing one-second fixture marker, not recorded
as a pass; the replacement test holds exactly 16 pending starts before checking rejection of
the seventeenth, without changing product deadlines.

Encrypted Git/outbox regressions passed 41 tests, including actual effects before queued source
changes and controlled overflow. Capability/file/terminal/browser integration passed 59 tests;
final focused frame cases passed after restoring the post-seal buffer fence and distinguishing
cancellation before versus during sealing. These transport completions establish handoff to
the Relay, not an acknowledgement by the device.

The isolated emulator, all test servers and the private ADB server were stopped, device fixture
credentials removed, and display/locale settings reset. Screenshots/logs remain available as
local evidence; transient pairing files were removed. No production GitHub action or deployment
was performed.


The final full Android unit run on App source commit `06595fc` passed 3,162 tests across
244 test-result files, with zero failures, errors or skips. The APK build and the opt-in actual
Android runs described above passed separately; unit results do not substitute for physical
Samsung, production navigation or actual-Pi qualification.
