# Actual IME with the paged editor and real SDK

The private Native and managed RPC `ime-window` cases passed the actual Android
system-IME, paged editor, repository, Host, original Source ACK and SDK chain.
They address the selected IME/navigation/large-paste and timing gate in the
[viewport plan](../plans/2026-11-01-editor-window-viewport.md), building on the
[small-field feasibility proof](real-ime-feasibility.md). Public capabilities
remain disabled; the full environment and physical-device qualification are open.

## Exact interaction

Both runs load the real 1 MiB Source (`U+1F600` repeated 262,144 times). The selected
debug InputMethodService uses its actual system currentInputConnection, not a
manually created connection or semantics SetText. The command composer is also
filled through the actual IME, and its Send button must be visible and enabled
while the controlled keyboard is shown.

The driver selects the first emoji, composes `ni`, replaces that composing region
with `U+306B`, and observes genuine composing/selection callbacks. Next navigation
while composition is active creates a fresh input generation. A stale generation
is explicitly rejected without editing the draft. Selecting and committing `XY`
through the new connection must alter only that new window's first emoji. This
behaviour corroborates that the old composing region did not carry into the new
window; extracted text itself does not expose composition spans, and Android is
not required to emit a redundant selection callback at session start.

First navigation returns to a 2,048-UTF-16-unit window containing the first edit,
1,023 emoji and `X`. A separately labelled programmatic 4,096-character `p` paste
replaces that entire window, retaining the `Y` marker and untouched suffix.
Rewindowing displays exactly 1,024 `p`, `Y` and 511 emoji: 2,047 UTF-16 units with
no split surrogate pair. Last/First navigation preserves the full draft. Normal
UI Send delivers exactly 4,096 `p`, `Y` and 261,119 emoji to the actual SDK:

- UTF-8 bytes: 1,048,573.
- SHA-256: `88a509ee4f941113c893e4b8406193660bf856a6ebfe262d39c65d4aae298a73`.
- One actual begin, 22 contiguous chunks, one commit and one SDK result.

The independent verifier reconstructs all uploaded bytes, correlates Android
begin-result upload ID with chunk/commit IDs, and separately binds the raw Source
begin/ACK/commit/ACK transfer ID. Source and Android transfer IDs are not assumed
to be identical. Initial full Source, final full draft, question/grant/session,
owner verification and SDK nonce/value hash are checked. Positive-outcome support
is deliberately not negotiated in this case; its optional owner-incarnation
binding is not invented for this transport proof.

## Local timing and presence evidence

| Run | Duration | Main-queue samples | Maximum / p95 sampled delay | Actual Native presence |
| --- | --- | --- | --- | --- |
| `native-ime-window-04` | 56.813 s | 47 | 48.252 / 2.151 ms | 10 accepted over 46.280 s; max gap 5.594 s, below existing 15 s TTL |
| `rpc-ime-window-01` | 57.428 s | 47 | 63.260 / 25.526 ms | 0; Native editor capability not negotiated in this RPC run |

The passive observer records real request/result timestamps. One-Hz main-queue
probes retain every scheduler tick, completed enqueue/completion times, skipped
pending ticks and original pending enqueue time; the summaries are recomputed by
the verifier. This is sampled delay evidence, not continuous frame profiling or
a general device performance SLA. Actions are controlled injected input paced at
650 ms, followed by a visible-foreground observation hold; they are not evidence
of physical human typing. Native original-editor calls stayed zero throughout
observed active work and after the SDK result. No lease was extended artificially.
RPC's native-presence absence is recorded rather than presented as equivalent
Native lease evidence.

## Pins and cleanup

Both runs use the same App APK
`05b77d897b307996b1ab720137f33b2ae4fafc4687684b8c93c3124964e3ceb3`
and Android-test APK
`28c19b2b70c35c1ac646303eaa17b05f4620662c24687b277c0711160af252e4`,
with 58 source snapshots plus controller/helper and installed/build APK checks.
Final build `ime-window-build-06.log` and final Host TypeScript check
`ime-window-tsc-02.log` passed, as did formatting, diff checks and scoped review.

Root controller `/private/tmp/run-pocketpi-ime-window-v2.py` restored the exact
prior default/enabled IME settings before final app clear. Native Host 32332,
SDK 32354, App 31801 and IME 31857 were independently observed absent afterwards;
RPC Host 32551, SDK 32580, App 32301 and IME 32357 were also absent.

Saved evidence is under `/private/tmp/pocketpi-paged-production.oge2ybh1`, including
both proof files, raw Host traces, source/APK snapshots, composer screenshots and
`ime-window-both-independent-audit.txt`/`.json`. Independent script
`/private/tmp/verify-pocketpi-ime-window.py` passed for both labels.

Failed Native attempts remain failed: 01 exposed the pre-editor command not being
consumed; 02 screenshots diagnosed the debug bare View filling almost the whole
screen (2,264 px IME inset, zero visible action bounds). The debug IME now measures
its desired 48 dp explicitly. Attempt 03 confirmed the corrected 126 px geometry
but hit a diagnostic helper that incorrectly required the old action tag after
Send. That helper now tolerates a legitimate control replacement after the click;
strict pre-click visibility/geometry and draft-consumption checks remain. No main
product code or authority guard was changed for those fixture corrections.

These selected local cases do not make historical value/authority/recovery cases
universally current, close every viewport-matrix scenario, or qualify physical
Samsung/DeX, vendor keyboards, an independent Linux host, or general performance.
Reconcile the original acceptance matrix using the actual scoped evidence before
production enablement; do not silently broaden this passing result.
