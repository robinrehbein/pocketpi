# Real system IME feasibility

`real-ime-smoke-03` passed the opt-in small-field Compose smoke, controller
cleanup, verified keyboard restoration and independent saved-trace verification.
This establishes the controlled IME seam for the
[viewport qualification plan](../plans/2026-11-01-editor-window-viewport.md).
It does **not** complete the real paged-editor/SDK chain, timing gate, or physical
Samsung/Gboard/Samsung Keyboard qualification.

## What was exercised

Android actually registered and selected the debug-only `PocketPiTestIme` service.
It ran in a separate `:pocketpiTestIme` process and used its system-provided
`currentInputConnection`. A non-exported same-UID provider serialized bounded,
generation-bound commands onto the IME main thread. No semantics SetText or
manually created InputConnection was used for edits.

The saved trace contains genuine startInput/startInputView and updateSelection
callbacks: selecting `beta`, composing `ni`, replacing that composing region with
`に`, finishing composition, then committing `!`. Actual Compose TextFieldValue
states corroborate text, selection and composition ranges. Recreating the field
created a new input generation in the same service process. The old generation
was rejected with the exact `stale generation` response and unchanged text; a
new-generation selection and commit succeeded. The rejection response and before/
after states are saved independently of the assertion code.

The provider rejects other UIDs and other packages' focused fields, limits text
and trace size, fails on trace overflow, and distinguishes a queued command
cancelled before start from a timeout with unknown outcome. Tests never retry
unknown actions. These are debug/test facilities, not product keyboard controls.
A secondary application process runs the app's existing startup routines; this
fixture is therefore restricted to the owned disposable emulator and app data.

## Recorded evidence

- App baseline `b93dd183`, Host baseline `c9eb2021f`; both Git-first checks and
  fast-forward pulls completed before work. No Host source changed in this slice.
- Final Android debug/test build `ime-smoke-build-03.log` passed. Root runtime
  `real-ime-smoke-03` passed in 7.528 seconds, JUnit OK (1 test).
- Independent verifier `/private/tmp/verify-pocketpi-ime-smoke.py` passed against
  saved source/APK/controller snapshots, 21 actual IME events, ordered field states,
  exact stale-generation rejection and restored default/enabled keyboards.
- App PID 29925 and IME PID 29967 were independently observed absent afterwards.
- App APK SHA-256: `2826a7669f68b67febbb515d7a76efbb9b65da39166a53f64aeba585259d8b0b`.
- Test APK SHA-256: `10758334c0c248179013a74423623cef6e2e2596fce94074414f015b2249f529`.
- Private evidence root: `/private/tmp/pocketpi-paged-production.oge2ybh1`;
  controller `/private/tmp/run-pocketpi-ime-smoke-v3.py`.

Root saves the old default and enabled IME settings before mutation, restores and
verifies them before the final app clear, and preserves recovery state if restore
fails. The previous emulator keyboard and enabled subtype list were restored.

Diagnostic attempt 01 stopped before selection/instrumentation: `ime list -s`
listed only enabled methods. Reading `ime list -a -s` confirmed installation.
Attempt 02 ran the actual test successfully, but its controller's final evidence
write failed with ENOSPC; it is **not** a complete passing controller run. Root
independently confirmed restoration and absence of both processes. To recover
space, 407 byte-identical owned APK snapshots were replaced by read-only hardlinks
only after SHA-256 equality checks; every evidence path and content was retained.
The mapping is recorded in `identical-apk-snapshot-links-1793597559.json`. No
unrelated files were removed. Attempt 03 adds a 1-GiB free-space preflight and the
complete saved stale-response record, and is the qualified run.

## Next original gate

Use this actual IME with the real paged editor, 1 MiB source and repository/Host/
SDK chain. Exercise composition and selection across window navigation/session
restart and a separately labelled large paste, checking exact full-value hashes.
Measure actual presence request/result timing and all main-queue samples during
paced edits. Preserve Native/RPC scope and separate injected editing from physical
human typing evidence. Public paged/positive capabilities remain disabled.

The service follows Android's documented
[InputMethodService lifecycle](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)
and [InputConnection composition contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection).
