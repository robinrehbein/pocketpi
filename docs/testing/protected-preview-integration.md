# Protected preview integration qualification — 2026-10-31

PocketPi now exposes an independent Preview action for negotiated v3 sessions. The user selects a locally granted development-server port and receives a captured Chromium view over the existing encrypted transport. Browser and preview clients, grants, profiles, drafts and ownership remain separate. Reconnect preserves the endpoint/tab and unsent draft as a reader; it never replays input or automatically reacquires control.

The host owner enables `config preview-control on`, configures an available absolute Chromium `browserExecutable`, then uses `preview grant <device-id> <project-id> <port>` on the running host. `preview grants` lists grants; `preview revoke <device-id> <project-id> <port>` removes one. Offline grant creation is refused. Enabling the switch does not grant a device an endpoint. Existing general-browser permissions are independent. No real user grant or configuration was changed during qualification.

The PreviewService validates active paired device, current actor/session, trusted canonical project/folder and exact port at execution and delivery. Runtime retention uses independently validated stable session sources instead of a dead socket. Endpoint count, retained authority count and failed-close resources are bounded. Initial-tab provisioning is owned before publication, and failed cleanup remains accounted for. Overlapping successful and failed opens of the same session preserve the successful source.

Preview configuration persists through an explicit writer transaction. Ordinary concurrent saves merge the last durable preview fields at queue execution; a failed owner save cannot leak an enabling grant through `push.register`, and a successful grant cannot be overwritten by an earlier ordinary snapshot. Deterministic storage tests and an actual encrypted host/device race cover this behavior. Other older local-control field families require a separate equivalent audit/fix before whole-product qualification.

## Evidence

- Mac Node 24.18.0: final required host check passed (92 existing warnings, 3 infos); full extension suite: 6,275 passed / 25 skipped in 289.08 s.
- Linux/arm64 Node 24.22.0 / Chromium 156: final focused suite: 167 passed in 14.35 s. Uses the existing experimental Moby-derived namespace profile, capabilities dropped, no-new-privileges and a private internal Docker network. This is local Linux subset evidence, not independent remote-host or production devcontainer qualification.
- App: full resource parity, 3,066 app / 34 Wear unit tests, lint, app/Wear/test APK and benchmark builds passed (`scripts/quick-check.sh --full`, 334 s).
- Real encrypted Android-to-HostDaemon preview-only flow, actual Chromium: phone 1080×2400 / density 420 and tablet 2560×1600 / density 320: 10.525 s / 9.038 s passed on final APK; both screenshots visually inspected. Exercises form input, WebSocket DOM/frame update, actual TLS/WebSocket cut, same endpoint/tab restoration, preserved newer draft, no automatic control/input replay, explicit reacquire and active grant revocation. A standalone test activity supplies validated-network/foreground signals.
- Actual Vite 8.3.4 HMR: Mac and Linux tests modify `main.js`, require the self-accept callback marker and a changed captured frame. This is separate from the Android fixture's WebSocket DOM update; a combined Android/Vite workflow is not claimed.
- Independent review: host three bound rounds, app two bound rounds. Persistence, failed initialization/cleanup, endpoint-capacity and overlapping-open defects received regression tests and corrections. Final static reviews report no further actionable finding within this patch.

## Remaining qualification

Physical Galaxy S25/Tab S11, DeX/hardware input, activity/process death, real radio/network transitions, device-switch and long sessions remain untested here. The standalone view test does not qualify the entire production navigation lifecycle. Browser support still depends on an awake, reachable, configured host. Android/Wear and Mac iOS simulator interactive streaming, secure packaged remote-Linux/devcontainer parity and the complete ticket-to-PR/CI acceptance workflow remain full-goal gates. No deployment, release, push, PR, external ticket publication or production approval follows from these local tests.

## Local evidence paths

Mac full suite/check: `/private/tmp/preview-integration-final-host-suite.log`, `/private/tmp/preview-integration-final-host-check.log`. Linux: `/private/tmp/preview-integration-linux-separated.log`; runner `/private/tmp/pocketpi-linux-environment/verify-preview-integration.sh`. App: `/private/tmp/preview-integration-final-app-check.log`. Final Android flows: `/private/tmp/preview-integration-final-phone-separated.log`, `/private/tmp/preview-integration-final-tablet-separated.log`; sanitized counters `preview-integration-final-{phone,tablet}-proof.json` and screenshots `pocketpi-preview-final-{phone,tablet}.png` under `/private/tmp`.

A simultaneous heavy-build run produced a timeout in an existing Linux capped-stream test and an Android revoke HTTP timeout; the latter had actually completed revocation at the host. Both final separated runs passed. These failures remain in `/private/tmp/preview-integration-linux-final-overlap.log` and `/private/tmp/preview-integration-final-source-phone-correct-runner.log`; passing reruns do not qualify performance under saturation. An initial manual instrumentation invocation used the wrong runner name; the qualified runs use the installed `PocketPiTestRunner`.
