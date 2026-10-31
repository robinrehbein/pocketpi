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

## Required runtime gates

The corrected encrypted Android push and intentional lost-reply/reconnect/status-query scenarios passed for the English phone fixture. Phone/tablet English/German screenshots must be inspected separately. Physical Galaxy S25/Tab S11, hardware input/DeX, independent Linux/devcontainers and actual Pi session qualification remain separate gates.

## Reproduce the isolated fixture

Use the host fixture documented in `packages/pi-atlas/extensions/remote/runtime-test/git-android-e2e/README.md` in the companion monorepo. Opt in with `--with-push`; use a fresh fixture with `--drop-push-reply` for the recovery scenario. Run `GitPushTransportEndToEndTest` with `gitFixturePath`, `gitPushFixtureExpected=true` and the matching `gitPushDropExpected` mode. Keep fixture credentials outside the repository and remove them after use.
