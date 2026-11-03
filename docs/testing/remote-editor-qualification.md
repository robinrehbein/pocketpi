# Remote editor qualification

Bounded inline RPC editors are locally qualified. Native and oversized/paged editor support
remain open. The starting commits were App `59d75e8` and Host `855e97302`; the Host change is
saved as `0cfd68d05`, and the App change accompanies this record.

## Implemented behavior

The Host preserves the full multiline prefill and returns the exact submitted value or cancellation
to its original RPC owner. Prefill is limited to 16,384 UTF-8 bytes and answers to 65,536 bytes;
invalid Unicode, disallowed controls and oversized text are rejected without truncation.
Only LF and tab are permitted C0 controls. Native and details-backed editor questions remain
unavailable through this bounded path.

Editor delivery requires a successfully delivered v3 opt-in. Legacy clients receive same-ID
`local_required` questions across events, snapshots and replay. The Host defaults
`questionEditorEnabled` to true and honors explicit false; it does not advertise a native editor
bridge. Android validates current question identity, connection, selection and capability before
sending, resets drafts on authority changes, and keeps Cancel available on supported editor cards.

## Automated verification

| Check | Result |
|---|---|
| Full Android unit suite | 3,368 passed, 252 classes; no failures, errors or skips |
| Wear unit suite | 34 passed, 4 classes; no failures, errors or skips |
| Repository quick-check | Passed; dependent Android, Wear and benchmark variants compiled |
| App and test APK packaging | Passed in 31 s |
| Covering Host suite | 980 passed, 5 skipped, 30 files, 105.83 s |
| Extension TypeScript checking | Passed |
| Scoped Biome | 11 TypeScript files passed without warnings |
| Both working-tree diff checks | Passed |

Protocol/projection, Host, Android and final integration reviews are complete. Regression tests
first reproduced the details bypass, stale peer ownership and late-bound question problems, plus
draft scope and capability-loss UI problems; fixes passed scoped rereviews. A concurrency concern
was withdrawn after confirming the shared session queue and passing a deferred-write test with
two devices for both RPC and peer owners. Earlier compilation failures were corrected before the
successful final quick-check.

## Actual Pi-to-Android verification

All four runs used the actual Host-owned Pi 1.4.0 `RpcProcess`, public `ctx.ui.editor`, encrypted
transport, production repository and rendered Android UI. Each passed JUnit with one test.

| Host setting | Emulator profile | Action | Time |
|---|---|---|---:|
| Explicitly enabled | Phone, English | Submit | 16.841 s |
| Explicitly enabled | Tablet, German | Cancel | 13.599 s |
| Default | Phone, English | Submit | 13.422 s |
| Default | Tablet, German | Cancel | 12.465 s |

Tests checked full Unicode/newline/whitespace prefill and the actual returned value or cancellation,
current owner/PID/session, `hostRpcVerified`, PR status transitions and reconnect
(one cut, two upgrades per run). Seven PNGs were retained per scenario; phone and tablet editor
views were inspected and legible. The fixture uses a private execution launcher and controlled
extension, with no model, tool execution or GitHub calls. An initial external-process setup would
have discarded RPC UI stdout; preflight switched to the actual Host-owned process before any
instrumentation, without introducing a synthetic RPC bridge.

## Cleanup and remaining gates

All owned fixtures and the emulator exited successfully. The private ADB server on port 5191
stopped; ports 5191, 5584 and 5585 had no listeners. Eight JSON/CA copies and the private Android
home were removed; fixture metadata, private fixture directories and actual owned Pi processes
were absent. Personal Host configuration and default ADB were untouched. No push, PR, merge,
deployment or release was performed.

Native editor lifecycle/fallback and oversized/paged prefill are separate next steps. These results
do not qualify MainActivity lifecycle behavior, real Samsung hardware, an independent Linux host,
production networking or the complete remote-development workflow. Physical devices and an SSH
testhost were unavailable; the overall goal remains active.
