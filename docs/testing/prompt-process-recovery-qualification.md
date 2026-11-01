# Prompt process recovery qualification — 2026-11-01

## Product behavior

An unacknowledged initial prompt retains a durable send journal. Independent provenance for text, quote and attachments is persisted in the same encrypted draft snapshot. Fields cleared by the send can be restored after repository/process recreation; actual user edits invalidate ownership for their field, including an edit away and back to the same value or an intentional empty value. An exact negotiated source-user delivery receipt resolves the journal without automatically sending again. Receipt cleanup erases a composer only when the complete current composer still has proven ownership; mixed user edits are conservatively retained.

Old files have no provenance. Their historical empty-composer restoration remains for compatibility, but it grants no permission to erase ambiguous text on receipt. Intentional deletion in those old files cannot be reconstructed from missing historical information.

## Regression evidence

Root first reproduced two failures: an untouched timeout-restored composer survived receipt after repository reopening, and intentional deletion was resurrected. Independent review then reproduced quote edits during a send, attachment removal while upload was held, and ambiguous empty legacy restoration/receipt cleanup. These failures preceded their respective fixes. The complete repository suite passes **567 tests**, and the required quick check passes **3,490 App + 34 Wear tests**, with no failures, errors or skips. Logs: `/private/tmp/pocketpi-process-restore-red.log`, `/private/tmp/pocketpi-process-restore-mixed-red.log`, `/private/tmp/pocketpi-process-restore-upload-red.log`, `/private/tmp/pocketpi-process-recovery-build-green.log`, `/private/tmp/pocketpi-process-recovery-quick-check.log`.

The final source was built in `/private/tmp/pocketpi-process-recovery-installed-build.log`, then both APKs were installed on the private emulator. The actual Android encrypted-store suite passed **10 tests**, including independent store recreation with provenance (`/private/tmp/pocketpi-process-recovery-stores.log`). Store recreation and repository recreation are not process-death evidence.

## Actual process qualification

The opt-in `PromptProcessRecoveryEndToEndTest` and private runner use a separate test-owned encrypted file and keystore alias. Phase A accepts one lifecycle prompt through a real native Pi worker while withholding the result and source echo, observes the disconnected uncertain composer, flushes the journal, and remains alive. The external runner verifies the live app PID, force-stops it and confirms that PID is absent from a fresh package PID query. Phase B uses a distinct app PID, the same encrypted journal, and a receipt matching the original request and source message, with zero matching prompt write attempts after restart. The native worker identity and Host prompt count remain unchanged. The runner retains source/APK hashes and private phase proofs and removes its own copied credentials, key and files.

Four current-source runs completed successfully, including test-owned key/file/fixture cleanup:

| Layout / composer | Evidence label | Killed PID → new PID | Two-phase duration |
| --- | --- | --- | --- |
| Phone / untouched | `process-restore-untouched-03` | 19156 → 19309 | 16.580 s |
| Phone / edit away and back | `process-restore-edited-01` | 19490 → 19646 | 16.184 s |
| Tablet / untouched | `process-restore-tablet-untouched-01` | 19877 → 20039 | 16.891 s |
| Tablet / edit away and back | `process-restore-tablet-edited-01` | 20319 → 20485 | 17.758 s |

Artifacts are retained under `/private/tmp/pocketpi-paged-production.oge2ybh1`: each label has its result, phase proofs/logs, source and installed APK snapshots, final Host status and source lifecycle/model traces. Each run proves one accepted lifecycle prompt, zero matching Android prompt write attempts in the new process, the exact original source-user receipt in recovery, and the same native worker PID/session/runtime. At the cut, one result and two upserts for the same source message were queued, with none delivered. Model traces reject an unconsumed delivery envelope. Tablet runs use 1600 × 2560 at 240 dpi and restore the original 1080 × 2400 at 420 dpi.

The product build and broad checks precede all four runs. Test-only harness corrections were rebuilt in `/private/tmp/pocketpi-process-recovery-save-build.log` and installed before the first qualified run; no product/test source changed between the four successful runs. Independent artifact review confirms all four terminal results, phase proofs, matching source/APK hashes across layouts, and both display restoration records.

Two earlier setup attempts failed before sending the lifecycle prompt: `process-restore-untouched-01` mounted navigation before project resolution and timed out waiting for the composer; `process-restore-untouched-02` attempted a scroll action on the fixed Save button. Both cleaned up successfully. They are retained as failed attempts, not counted among the four passes. The test setup now follows the already-qualified navigation flow and clicks the fixed Save action directly.

## Scope limits

These are local Mac Host/native Pi and Android emulator tests with a controlled deterministic provider. The process test mounts production repository/navigation/transport in `NavigationTestActivity`; it does not exercise production `MainActivity` startup, monitor/background-service restoration, Host/Pi process death, a new-device handoff, an independent Linux machine, physical Samsung devices, an external model/provider, or external GitHub/CI. Wear emulator testing is excluded by request.
