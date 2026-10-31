# Protected preview foundation

## Scope, 2026-10-31

Host implementation: local commit `5341d14ae` on `codex/protected-previews`.

The host now has a separate `PreviewPolicy` with explicit device/project/numeric-port grants,
strict whole-list validation, a 256-grant bound, deep snapshots and serialized transactional
persistence. Disabled or malformed state denies access. Failed saves preserve the last effective
state and restore the raw key; queued changes continue safely after failure. Browser grants are
not reused. Nine focused tests pass on Node 24.18.0, including delayed saves, failures, overlapping
mutations and the capacity boundary. Native Node TypeScript import and exact-port smoke pass;
host extension checking passes with existing warnings. Independent review found no remaining blocker.

This policy is a foundation, not an exposed preview service: no daemon wiring, configuration schema,
CLI authorization commands, tunnel protocol, app preview UI or preview capability has been added.
The future transport must fix the destination to literal `127.0.0.1` and an explicitly granted port,
revalidate current device/session/project authority at every operation and delivery, and bound HTTP
and WebSocket streaming, cancellation, backpressure, redirects, cookies and request headers.
Network operations must never be automatically replayed after an uncertain result.

## Renderer feasibility gate

A separate APK, now retained under [tools/preview-isolation-probe](../../tools/preview-isolation-probe/README.md),
compares the same WebView construction/document load in a normal service and an isolated service.
The root built it offline and ran it on an API 36 emulator with WebView 133.0.6943.137. The control
(UID 10215, INTERNET granted) loaded the intercepted static page. The isolated service (UID 99000,
INTERNET denied) bound and completed early profile-suffix bootstrap, but failed WebView construction
with a null platform window-manager interface. This specific service/context approach is not ready
for product use. It does not establish impossibility for other renderers or runtime versions.

An ordinary `:preview` process separates WebView profile/proxy state but retains the app UID and
INTERNET permission. It cannot be presented as an OS network sandbox. Existing artifact and terminal
WebViews keep their permanent network lock; no relaxation was made. API 26–35, UI embedding, HTTP,
fetch POST, WebSockets, HMR, cookie/storage isolation, DNS/UDP bypasses, revocation and physical
Samsung acceptance remain untested by this construction probe.

Root evidence: `/private/tmp/preview-policy-final-root-tests.log`,
`/private/tmp/preview-policy-host-check-final.log`, `/private/tmp/preview-isolation-probe-run.log` and
`/private/tmp/preview-isolation-webview-provider.log`. The preserved probe is independent of
PocketPi's build and does not add components to its APK.

## Next implementation

Resolve a renderer approach that can support ordinary dev HTTP/fetch/WS/HMR while enforcing the
endpoint boundary: a proven permissionless Android renderer with brokered streams, or a separate
host renderer with constrained egress. Then implement the authenticated bounded tunnel and local
owner grant commands, wire the daemon lifecycle and add app UI. Verify both artifact/preview startup
orders, exact endpoint denial, cookie/storage separation, active-stream revoke and process loss.
Do not advertise preview support until the complete path works. The full remote-development goal
remains active, including remote Linux/devcontainer, emulator streams and physical device gates.
