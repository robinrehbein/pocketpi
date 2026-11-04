# Real IME qualification implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development with scoped authors and independent review.

**Goal:** Qualify actual Android input-method composition, selection and editor-session lifecycle before extending the real paged SDK chain and timing evidence.

**Architecture:** Debug-only InputMethodService is selected by Android on the task-owned emulator. A non-exported same-UID provider in the IME process controls its actual currentInputConnection and records genuine lifecycle/selection callbacks. An opt-in smoke test first proves this seam; full paged/SDK qualification follows only once it works.

**Tech Stack:** Android InputMethodService/InputConnection, debug ContentProvider, Compose TextFieldValue, instrumentation and isolated ADB.

**Spec:** `docs/plans/2026-11-01-editor-window-viewport.md`, actual IME and timing gates.

## Global constraints

- No main/release behavior changes or public capability enablement; debug/test sources only.
- Actual system-bound IME and currentInputConnection, no manually created connection or semantics text replacement.
- Root owns all Git/build/ADB/runtime actions; save and restore previous default/enabled IME state in a finally path.
- Only PocketPi_Paged_Private on ADB5191/emulator5584; no default ADB or personal devices.
- Control provider non-exported and same UID; actions limited, bounded and generation-bound. Never edit another package's focused field.
- Smoke coverage is feasibility evidence, not actual paged/SDK or Samsung/Gboard typing qualification.

## Review focus

- Separate IME process must communicate over real IPC; no instrumentation static-field assumption.
- Real callback composing bounds and selection must agree with actual Compose state.
- Old generation actions must fail rather than reach a replacement editor.
- Timeout/failure must restore the original keyboard and preserve diagnostics.
- Injection pacing and emulator results must not become claims about physical typing performance.

### Task 1: Debug IME and protected control seam

- [x] Add service, input-method metadata, separate-process non-exported control provider and bounded real-event trace.
- [x] Review against Android official InputMethodService/InputConnection contract and compile debug APK.

### Task 2: Actual IME feasibility smoke

- [x] Focus a real Compose TextFieldValue field; select a range, compose twice, observe actual composition callbacks, finish and commit.
- [x] Recreate input session, prove new generation and rejected stale commands; save exact field/IME traces.

### Task 3: Root capability validation

- [x] Install on owned emulator, explicitly select the service, execute the opt-in smoke and independently verify the saved trace.
- [x] Restore original default and enabled state even on failure, verify process cleanup, record limitations and local commits.

### Task 4: Real editor chain and timing

- [x] After feasibility, use actual paged 1 MiB source and Android repository/Host/SDK. Exercise composition, selection, navigation/session restart and large paste through the real IME.
- [x] Record exact full draft and SDK hashes plus original ACK, actual presence request/results and all main-queue samples during paced edits. Preserve Native/RPC scope and separately label injected paste.

Design choice: A direct test call to AndroidComposeView.onCreateInputConnection is useful debugging but does not meet the actual-service gate. A debug-only system service gives a reproducible platform-lifecycle seam without introducing keyboard controls into the product. Physical Samsung keyboard implementations and hardware remain separate unavailable tests.

Tasks 1–3: actual `real-ime-smoke-03` and independent verification passed; see `docs/testing/real-ime-feasibility.md`. Task 4 selected Native/RPC actual IME cases now pass; see docs/testing/real-ime-window.md. Full environment goal remains active.

## Task 4 execution contract

The private `ime-window` Host case keeps the original 1 MiB emoji Source and
expects exactly 4,096 `p` characters, `Y`, then 261,119 emoji (1,048,573 UTF-8
bytes). Actual IME actions select/compose the first emoji into `ni` then `に`,
navigate Next while composition is active, and replace the next window's first
emoji with `XY`. Returning First and replacing the entire visible 2,048-unit
window by a 4,096-character programmatic paste must leave the `Y` marker and
untouched suffix exact. Rewindowing, Last/First navigation and final normal Send
must preserve the full draft and actual SDK hash.

The test captures actual lifecycle generations, composing/selection callbacks,
stale-command rejection, bounded displayed windows and full repository hashes.
All 1-Hz scheduler ticks, skipped ticks and outstanding main-queue post timestamps
are retained; completed delays are reported without inventing a new device SLA.
Native active heartbeat acceptance must be observed over at least 20 seconds,
with the existing lease and no original-editor fallback. RPC records its actual
capabilities and traffic, without synthesizing Native heartbeat semantics.
Root archives pinned APK/source/controller, raw Host and Android traces, restores
prior IME settings before final app cleanup, and independently audits each mode.
This controlled injected input is not evidence of physical human typing or vendor
keyboard compatibility, and does not replace other original acceptance cases.

## Task 4 diagnostic findings

The first Native attempt stopped before the SDK editor opened: the command was
entered through the IME but the primary action was not consumed. Targeted
screenshots in attempt 02 showed a blank debug IME covering almost the entire
screen (2,264 px inset, zero visible button bounds). The bare debug View used
Android default measurement, which filled the available AT_MOST constraint;
layout parameters alone did not enforce 48 dp. The debug input View now measures
its desired 48 dp explicitly. The test verifies actual IME geometry and visible
positive button bounds before clicking, without hiding the keyboard as a workaround.

Attempt 03 measured the corrected 126 px strip and positive button bounds, but
the diagnostic helper assumed the old primary action tag remained after sending.
The helper now records absent post-click controls without failing; strict
pre-click geometry, visibility and activation checks remain. Failed runs retain
their own evidence and successful keyboard/process cleanup; none is a passing
full chain. The final test also uses an independently specified 2,047-unit
post-paste window, avoiding a surrogate-splitting 2,048-unit test oracle.
