# Session epoch and replay integration qualification — 2026-11-01

## Tested integration

The previous qualified App branch remains at `e58067f`. The separate local branch `codex/remote-environment-app-upstream-nov01` integrates `origin/main` at `3c1cd58`, including TOOLING-1107 list epochs, TOOLING-1108 compaction/replay deduplication and the shared Host resume fixture. The Host remains at `d66f848d8` on `codex/remote-environment-upstream-nov01`. The initial App ff-only pull refused divergence; no reset, rebase or overwrite was used. No remote push, PR merge, deployment or release is included.

## Resulting behavior

- A known, page-confirmed retired epoch cannot advance the selected timeline or revive through an old page. A previously unseen event only provisionally displaces the current epoch; a causally later authoritative page can restore the actual current epoch. Opaque epoch IDs are never sorted to infer age.
- An ordinary read records its observation boundary. A page overtaken by a quarantined event is rejected before changing content or settling questions, delivery receipts, task freshness or stale-screen state. A same-epoch page must cover the observed revision; a fresh read started after the observation can establish a different, non-retired epoch. Held preselection reads are conservatively treated as overtaken.
- Rejected reads cannot release offline queued prompts. Recovery is bounded rather than repeatedly requesting contradictory pages; a later new epoch starts a new attempt. A retained timeline seeds its previously confirmed epoch after reconnect, so accepting a replacement still retires its predecessor.
- Titles have the same provenance requirements as statuses. A known confirmed title is retained while another epoch is provisional; a newer confirmed title or applicable successful local rename wins. A title for a background chat with no directly visible baseline is held as a bounded candidate and published only if its epoch is confirmed. Valid cross-project tests check the cached/aggregate title before a new list read can mask an error. Missing or malformed epoch fields retain the existing legacy treatment.
- Editor close receipts use the exact question instance captured when its close actually applies inside the timeline, independently of general effects already executed on live receipt. Replay and draining a failed wait cannot skip the original close or bind it to a same-ID replacement.

Lists without a confirming page still use bounded provisional ordering. Epochless Hosts keep their established revision behavior. The tracker is bounded; this is not a claim of arbitrary ordering of infinitely old, previously unknown epochs.

The upstream test allowing an old page to diverge selected and list status was refined: its event-only B is provisional, so a later authoritative A can restore A. A separate test first confirms B and proves that genuinely retired A cannot replace it.

## Automated verification

The final required quick check passes **3,527 App + 34 Wear tests**, with zero failures, errors or skips (`/private/tmp/pocketpi-epoch-accepted-final-quick-check.log`, 88 s). Final APK builds pass (`/private/tmp/pocketpi-epoch-accepted-final-apks.log`, 15 s), and both APKs were installed before the final runtime matrix. Independent static reviews cover retirement, causal boundaries, reconnect, queued dispatch, exact question identity, titles and the corrected fixture histories.

Regression-first evidence is retained:

- `/private/tmp/pocketpi-epoch-replay-red.log`: three failures for selected-timeline poisoning, unknown-epoch mixing and a held editor close skipped by effect deduplication.
- `/private/tmp/pocketpi-epoch-authority-red.log`: two failures for provisional first pages publishing prematurely.
- `/private/tmp/pocketpi-epoch-provisional-retirement-red.log`: two failures for prematurely retiring confirmed A, including reopening a chat.
- `/private/tmp/pocketpi-epoch-reconnect-authority-red.log`: retained confirmation lost on reconnect.
- `/private/tmp/pocketpi-epoch-title-red.log`: provisional title surviving authoritative return; the valid rejected-read queue case passed.
- `/private/tmp/pocketpi-epoch-valid-fixture-isolated.log`: corrected malformed-epoch history and both valid cross-project title cases pass.

Intermediate failures remain failures. One real retry loop was diagnosed in `/private/tmp/pocketpi-epoch-replay-thread-diagnostic.log` and fixed by honoring the retry bound even when the old timeline still needs a snapshot. A later test loop (`/private/tmp/pocketpi-epoch-cross-project-thread.log`) came from injecting A revision 2 without recording its predecessors in the simulated A journal; the fixture now records the title at real A revision 1. An earlier queue fixture incorrectly supplied a replay to a fresh snapshot request; it now supplies a valid snapshot. Invalid missing-title fixture rows were replaced with valid titled sessions and an actual project switch. Those setup attempts are not counted as successful qualification or evidence of additional product fixes.

## Final runtime matrix

All six final runs passed, including cleanup. Evidence is retained under `/private/tmp/pocketpi-paged-production.oge2ybh1`.

| Case | Evidence label | Recorded runner duration | Result / cleanup |
| --- | --- | --- | --- |
| Phone / process, untouched | `epoch-final-process-phone-untouched-01` | 17.422 s | PASS / PASS |
| Phone / process, edited | `epoch-final-process-phone-edited-01` | 17.666 s | PASS / PASS |
| Tablet / process, untouched | `epoch-final-process-tablet-untouched-01` | 17.716 s | PASS / PASS |
| Tablet / process, edited | `epoch-final-process-tablet-edited-01` | 18.753 s | PASS / PASS |
| Phone / complete workflow | `epoch-final-workflow-phone-01` | 90.457 s | PASS / PASS |
| Tablet / complete workflow | `epoch-final-workflow-tablet-01` | 93.409 s | PASS / PASS |

The four process cases retain 35 runtime source snapshots each; the two complete workflows retain 33 each. Workflow-specific test files differ; all shared runtime paths match across the six runs. Independent artifact review confirms every pinned source against its saved copy and current source, both installed/build APK hashes across all six runs, exact phase/request/receipt/native-owner proofs and display restoration. No product or test source changed after the final APK build. Root inspected the final tablet success screenshot showing the interrupted reply, follow-up, successful shell and passing controlled PR check in the adaptive two-pane layout.

Process PIDs changed from 22978→23161, 23338→23495, 23722→23893 and 24157→24323 respectively, with phase-A absence verified after force-stop. Each new process made zero matching prompt writes and recovered the one accepted prompt. The complete phone/tablet JUnit timings were 88.185 s and 91.101 s respectively (runner durations above include driver overhead).

Earlier `epoch-process-*` / `epoch-workflow-*` passing candidates precede the final retirement/title corrections and are not final-source qualification.

The process runner retains 35 runtime source snapshots, installed/build APK hashes, separate phase proofs, final Host status and native lifecycle traces. Phase A proves acceptance with no result/echo delivered, flushes its own encrypted journal and remains alive until the external runner verifies and stops its PID. Phase B proves a distinct PID, an exact original source receipt, the persisted composer result and zero automatic matching prompt writes, with the same native worker. Test-owned key/files/credentials and fixtures are cleaned up.

The complete workflows add real Stop and follow-up, file editing, native agent tools, shell, a local commit/push and controlled PR/check states. Each captures four mandatory screenshots. Tablet runs configure 1600 × 2560 at 240 dpi and restore 1080 × 2400 at 420 dpi.

## Qualification boundaries

The event-ordering invariants are established by deterministic transport tests. The actual workflows use a local Mac Host, real native Pi and a controlled deterministic provider; Git remote and PR/CI are local/controlled. Process tests use production repository, transport and encrypted storage in `NavigationTestActivity`. They do not establish production `MainActivity` startup/services, Host/Pi process death, an independent Linux host, physical Galaxy hardware, external model/provider behavior or live GitHub CI. Wear emulator testing remains excluded by request. The overall remote-development goal is active.
