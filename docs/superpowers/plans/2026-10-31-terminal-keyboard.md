# Terminal keyboard extension implementation plan

> Use subagent-driven-development with bounded Terra tasks. Main owns integration and serialized Android checks. This is part of the approved remote environment goal.

**Goal:** Deliver Pi shortcuts through touch and hardware keyboards while preserving terminal input ownership, draft safety and protocol compatibility.

**Architecture:** Keep terminal v1 unchanged. Add a separately negotiated structured-key command that uses the existing attachment, authority and input sequence. The host encodes keys according to its current terminal modes.

**Spec:** `docs/remote-development-goal.md`.

## Current evidence

The installed public `@earendil-works/pi-tui` 1.4.0 `matchesKey` API was probed with Kitty mode both enabled and disabled. CSI-u forms for Ctrl+C/O/T, Alt+Enter/P and Shift+Enter were recognized in both states; Ctrl+Left used its modified cursor form. Legacy Alt+Enter and Alt+P were rejected when Kitty mode was enabled. The local result is `/private/tmp/pocketpi-keyboard-probe.json`; this is parser evidence, not a full Pi/device qualification.

The [Kitty keyboard protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/) is the encoding reference. Runtime mode tracking already exists in `host/terminal-modes.ts`. Pi core must remain unchanged.

The existing Android navigation intercepted Escape before the terminal field. A failing navigation regression reproduced it. The current focus fix yields field keys to the terminal, consumes Escape release, preserves F6 pane cycling and disables keyboard ownership for hidden retained terminal content. This fixes routing, not missing Ctrl/Alt wire support.

## Contract decision

Add `session.terminal.keys.v1` only on the v2 capabilities route, and only while terminal control is enabled. Keep `session.terminal.v1` for attach/output. The new `session.terminal.key` command carries `requestId`, `sessionId`, `attachmentGeneration`, `inputSeq` and `key`.

`key.name` is one printable ASCII character or one of the v1 named keys plus `f1` through `f12`. Optional `ctrl`, `alt` and `shift` are literal true when present. Optional `shifted` is one printable ASCII character produced by the device layout, only with `shift: true` and a printable base name. Letter names use lowercase unshifted identity. Uppercase names with Shift are rejected. Legacy encoding may infer alphabetic uppercase, but must not infer US punctuation for a German keyboard; shifted digits or punctuation need explicit `shifted` or an unsupported result before writing. Optional `repeat` is an integer from 1 through 16. Reject extra fields, controls in names and invalid combinations. No raw bytes or escape sequences are accepted from the device.

The command uses the same input sequence as text/paste/v1 keys, the same captured peer/runtime/attachment binding, project/device grant, rate budget, duplicate suppression and cleanup. An unsupported encoding must fail before writing or advancing the accepted sequence. Do not silently turn an unsupported chord into another key.

Encode legacy keys when that mode applies and Kitty/modifyOtherKeys forms according to the worker's observed mode. Expose only the needed read-only mode getters. Test against the installed public Pi key matcher and actual PTY input. A parser-only match does not prove shell/TTY signal behavior.

## Task 1: Host protocol and key encoding

Files: remote `protocol/terminal-keys.ts`, `protocol/parse.ts`, `protocol/types.ts`, `host/terminal-modes.ts`, `host/terminal-bridge.ts`, `host/daemon.ts`, their tests, shared `terminal-keys-v1.json` fixture and protocol README.

- [x] Add strict command/fixture tests first and observe the missing-route failure.
- [x] Add the separate capability and command; preserve the byte-identical terminal-v1 fixture and all v1 rejections.
- [x] Implement mode-aware structured encoding through the current bound input path. Cover raw Ctrl+C in a normal PTY, Kitty Alt+Enter, modified arrows, function keys and unsupported combinations.
- [x] Exercise the actual encrypted daemon path with grants, stale generation, duplicate input, revocation and mixed v1/new-key sequences. Assert the maximum advertised capability list fits the client's 32-entry limit.
- [x] Run focused tests, then the Remote suite, extension check and root formatting. Main commits the host and obtains independent immutable-diff review before committing Android integration.

## Task 2: Android input, touch controls and pacing

Files: `TerminalProtocol.kt`, `TerminalUiPolicy.kt`, `TerminalKeyboard.kt`, `TerminalPane.kt`, `DefaultRemoteRepository.kt`, localized strings, fixture and relevant tests.

- [x] Parse/validate structured keys and require the v2-only addon; old hosts keep existing controls.
- [x] Forward hardware modifiers and function keys while the field has focus, retaining F6 as a local pane-cycle escape hatch. Preserve AltGr text entry and do not reinterpret Android/Meta system shortcuts blindly.
- [x] Add touch modifier controls and common Pi chords. All supported chords must remain reachable without hardware. Make modifier state visible and accessible.
- [x] Ctrl/Alt actions must not implicitly send or discard the local draft. Enter variants may atomically queue the draft followed by the chord. Reject the whole batch when it cannot fit.
- [x] Pace input and coalesced resize together below the host's shared ten-per-second limit. Allocate input sequence only at dispatch, preserve order, and never replay uncertain writes after a connection change. Test with a virtual clock and a fast ACK source.
- [x] Test actual focus routing, hidden-tab ownership, touch/hardware parity, draft preservation, bounds and old-host refusal; then run one serialized quick-check and an independent scoped review.

## Acceptance still open

Actual unattended Pi dialogs and shortcuts on Galaxy Tab S11/S25, Mac and Linux/devcontainer PTYs need qualification. This keyboard increment does not implement shared browsers, previews or emulator streaming, and does not complete the full goal.


## Verification checkpoint on 2026-10-31

Host implementation commit `6dc453c5995aa65c03eebd819c4111ad993d1441` passed the full Remote suite:
2,858 passed and 23 skipped. Extension checks and root formatting passed. Independent review
verified the exact immutable committed diff. The existing terminal-v1 fixture is unchanged.
Android passed `scripts/quick-check.sh` with 2,954 app tests and 34 Wear tests, all successful;
shared terminal-v1 and terminal-keys-v1 fixtures are byte-identical to the host copies.

An isolated public Pi 1.4.0 CLI/PTY smoke observed Kitty flags 7 and bracketed paste, but could not
identify the expected settings/shortcut screen effects. Its result is inconclusive, not passed.
Public key-matcher, normal-PTY SIGINT, encrypted daemon and local UI/state tests do not substitute
for that complete runtime workflow or the physical Samsung and Linux qualification above.


## Compact Samsung window follow-up, 2026-11-01

The original three stacked touch-control rows left only 48 dp for the real renderer
inside a 320-dp pane. A first compact layout still left only 72 dp when a multiline
draft, modifiers and pending-input status were combined. Both were observed on
the owned emulator before their respective fixes.

Keep the established explicit draft/queue/key protocol. Basic navigation keys stay
in one horizontal strip; advanced modifiers, Pi chords and function keys move to
a scrollable Shortcuts dialog. Show active modifiers in the header only while the
current attachment supports them. Under 400 dp, render one input line; at larger
heights render up to three, retaining the entire draft and its existing send rules.
No immediate IME-to-PTY streaming mode is introduced.

Acceptance for this local layout increment: real renderer height at least 96 dp
at 320 dp with multiline text, active modifiers and pending input; the same minimum
at 280 dp with multiline text and modifiers without pending status; visible input,
Send and Esc, retained full draft through resize/dialog changes, F12 reachable by
scrolling, and older-host/capability-loss behavior. Physical Samsung/DeX and live
terminal ownership/network qualification remain separate. See the
[compact terminal record](../../testing/android-terminal-compact.md).
