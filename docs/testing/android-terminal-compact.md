# Android compact terminal controls

Status: locally qualified for the recorded component and native workflow cases. This follows the approved
[terminal keyboard plan](../superpowers/plans/2026-10-31-terminal-keyboard.md) and
Samsung phone/tablet priority. It does not change terminal protocol or input ownership.

## Behavior

The basic key strip and explicit text input remain available in the terminal pane.
Advanced keys, modifiers and Pi shortcuts are in a scrollable localized Shortcuts
dialog. Selected Ctrl/Alt/Shift remain visible in the header while supported by
the current attachment. Losing that capability hides the inactive indicator and
advanced entry point. The stored preference, draft and queue are not rewritten.

The input displays one line below 400 dp available pane height and up to three
above it. The complete multiline text remains editable and is sent only through
the existing explicit draft/Enter policy. Collapse, resize and opening/closing the
dialog do not send or discard it. Existing structured-key negotiation and guarded,
paced input queue remain in place.

## Regressions and local evidence

The original real TerminalPane/WebView left 48 dp for the renderer in a 320-dp
pane. Moving advanced controls into the dialog improved the simple case, but a
combined four-line draft, three modifiers and pending-input status still left
72 dp. The adaptive field fixes that observed combined case. A further regression
showed the Ctrl header indicator surviving capability loss; it now reflects only
effective modifier support. All three behavior failures were observed before
their respective fixes.

`TerminalPaneCompactUiTest` measures the actual WebView and checks complete draft
preservation, visible Send/Esc, modifier selection, far function-key scrolling,
legacy capability absence and the transition after capability loss. It uses the
real pane and renderer with UI repository/settings fixtures, not a remote Pi.
The 96-dp minimum is asserted at 320 dp with the combined pending-input state and
at 280 dp without pending status. It is not a claim for every height, font scale,
system inset or error notice.

Historical `terminal-compact-green-02` passes four cases with source/APK pins and
three screenshots. Root inspected the expanded dialog and the narrow settled
view; the latter shows the real software keyboard open. The initial collapsed
capture caught renderer startup before its black background was painted and is
not settled renderer-paint evidence. The test host draws its component directly,
without MainActivity's complete inset/navigation scaffold. Its status-bar overlap
is not a MainActivity qualification result. Actual IME transitions, live key
payloads, hardware keyboard, physical Galaxy devices and DeX remain separate.

The invalid assertDoesNotExist extension import, exact-text fixture assertion and
ambiguous Ctrl text selector were test setup failures, not product failures. The
successful current compile uses the installed Compose member API. Modifier-chip
selectors now require a click action, avoiding the read-only header summary.

Private artifacts are in `/private/tmp/pocketpi-paged-production.oge2ybh1/`.
Final `terminal-compact-green-03` passes all five cases and cleanup, including
capability-loss regression. Nine source/resource/test snapshots, build/installed
APK copies and three screenshots are retained; the runner verifies current hashes
before cleanup. Root visually inspected the earlier settled narrow and expanded
captures, and the actual MainActivity screenshots below. The final quick-check
passes in 91 seconds with 3,642 app and 34 Wear tests, zero failures/errors/skips,
resource parity and affected source-set compilation. Both final APK builds pass.
Independent scoped source review confirms the combined-state and capability fixes;
no live key-payload guarantee is inferred from the UI repository fixture alone.

The existing actual native Pi/MainActivity workflow was rerun on these final APKs:

| Case | Display | Result / cleanup | Duration |
| --- | --- | --- | --- |
| `custom-terminal-compact-phone-01` | 1080 × 2400 / 420 dpi | PASS / PASS | 15.186 s |
| `custom-terminal-compact-tablet-01` | 1600 × 2560 / 240 dpi | PASS / PASS | 16.920 s |

Both confirm takeover from a fixture-owned local attachment, actual revocation and
stale local-input rejection, visible custom title/nonce/two choices, Down/Enter
selecting `second`, and a follow-up command in the same real Pi worker/session.
The source trace has exactly opened/rendered/down/enter/result/followup. Root
visually inspected both actual MainActivity screenshots. The tablet display was
restored to 1080 × 2400 / 420 dpi in finally. Independent audit
`custom-terminal-compact-independent-audit.json` verifies 43 source snapshots per
case, installed/build APKs, exact source events and all four own Host/SDK PIDs
absent after cleanup. These are the deterministic public custom component's
basic-key workflow, not arbitrary TUI, every advanced chord or physical devices.
See [native custom terminal qualification](native-custom-terminal-qualification.md).

Final app APK SHA-256:
`f9f52bdff268f86727a1f998d70c70003ff6c16600a6efc0e8ba9ed5354f6490`.
Instrumentation APK:
`99a0b7abb2ad4d4ab18b0754861736c5a4f1870ea66bc38ad8f1a1860e379547`.
The full remote-development goal remains active.
