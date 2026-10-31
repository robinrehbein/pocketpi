# Preview session and app integration plan

**Goal:** Make the qualified host renderer accessible from PocketPi through independently authorized preview grants, with current session trust, bounded frames/input and safe reconnect.

**Spec:** `docs/remote-development-goal.md`, `docs/testing/protected-preview-runtime.md`.

**Architecture:** Extend `browser.open` with optional integer `previewPort` and echo it only in `browser.opened`. A v3-only `session.preview.v1` capability negotiates this extension. Normal browser opens have no previewPort and retain normal grants. Host routing selects the server-owned preview record by browserId for all subsequent commands/frames; it never retries another authority after an authorization failure. A separate app BrowserClient/state/pane preserves isolation and independent drafts. The preview manager reuses BrowserService ownership/recovery while each project/port owns a separate ProtectedPreviewRuntime profile.

## Constraints

- Current actor, session incarnation, project/folder trust, enabled switch, paired device and exact port grant remain required at execution and delivery/seal boundaries.
- Stable network authority is independent of a dead socket and is valid only while at least one previously authorized device/session source remains valid for the exact endpoint. Shared project/port preview state is accessible only to devices explicitly granted that endpoint.
- Runtime authority must include known stable session/device/grant sources; prune invalid sources, bound their count and close when none remain. Per-participant session/actor checks remain in BrowserService.
- Do not reuse general browser grants, share its profile, accept agent actors for the preview route, expose CDP or relax artifact/terminal WebViews.
- Preview capability appears only on v3, only with actual runtime availability and an active device's grant for a currently visible project. Browser recovery negotiation may serve preview even when general browser is disabled. Existing v1/v2 capability limits remain unchanged.
- Host-local owner commands: `config preview-control [on|off]`, `preview grants`, `preview grant <device-id> <project-id> <port>`, `preview revoke ...`. Offline grant creation is forbidden; live host validates device/project. Effective settings update only after persistence succeeds, serialized with all other configuration changes.
- Preview reconnect is read-only, with no input replay or automatic acquisition. Reopening includes the exact port and validates its echo. Selection/host/project/port changes close stale pane state. General browser and preview drafts/streams cannot mix.
- No live user configuration, deployment, publication or physical-device qualification is implied by fixtures.

## Interfaces and ownership

- Protocol/CLI worker: `protocol/browser.ts` and its tests/docs, `host/storage.ts`, `host/cli.ts` and dedicated preview CLI tests. `BrowserCommand` open adds `previewPort?: number`, result opened adds same; new constant `SESSION_PREVIEW_CAPABILITY = "session.preview.v1"`. All old messages remain unchanged.
- Preview manager worker: new `host/preview-service.ts`/tests and a read-only `BrowserService.owns(browserId)` helper. Its options supply runtime, enabled/allowed/deviceActive, actor-bound resolve and connection-independent retentionSource, document delivery. Manager supports execute/captureAccess/guard/owns/disconnect/revalidate/close. It provisions the initial exact-endpoint tab before first record publication.
- Root: daemon/runtime adapter integration, local owner handlers, route selection, capabilities, lifecycle and actual encrypted smoke tests.
- Android worker: BrowserProtocol, BrowserClient and tests; independent preview client/state/repository actions and pane/menu with English/German resources and tests. Root owns all Gradle/emulator/full-suite work.

## Tasks

- [ ] Write failing protocol, authorization-manager and app-port isolation tests; independently observe failures before implementation.
- [ ] Implement strict compatible wire extension, owner CLI/configuration and bounded session/device-aware preview manager.
- [ ] Wire daemon preview authority, frame/document delivery, lifecycle and v3-only capabilities; test preview-only grants, cross-authority denial, revoke/pause/project/session changes and save failure.
- [ ] Implement separate Android preview entry, port input, client/state and frame/input/recovery behavior with capability gating and independent drafts.
- [ ] Build, run real encrypted phone/tablet flows including HMR-like server, cookie isolation and revoke/reconnect, and inspect rendered output. Actual framework HMR remains a separate explicit gate until exercised.
- [ ] Independently review combined stable patches, run required checks, commit locally and preserve exact evidence and remaining full-goal gates.
