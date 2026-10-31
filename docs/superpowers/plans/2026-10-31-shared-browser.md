# Shared browser and protected preview implementation plan

> For agentic workers: use the agreed bounded-agent execution and independent review process. This document specifies work; no browser or preview tunnel is implemented by it.

**Goal:** Give the agent and PocketPi control of the same host-owned browser tabs and login state, with explicit input handoff, and provide a separate protected project preview supporting HTTP and WebSocket applications.

**Architecture:** A host-owned browser service controls its own Chromium through a private local CDP connection. A versioned encrypted channel carries bounded frames and validated input, while a separately authorized project preview maps only registered loopback services. The host remains the authority for project access, leases, profiles and revocation.

**Tech stack:** Public Pi extension APIs, TypeScript host/protocol and opaque encrypted relay, Kotlin/Compose Android. Browser runtime availability and a secure preview origin are feasibility gates, not assumed dependencies.

**Spec:** `docs/remote-development-goal.md`; parent plan `docs/superpowers/plans/2026-10-30-remote-environment.md`.

## Evidence and constraints

- On macOS, Node 24.18.0 and a cached Chromium headless shell with a fresh temporary profile passed a loopback-only CDP input/click test, yielded five screencast frames and a screenshot, and reported 844 × 390 landscape. The test page lacked a viewport meta tag: 390 device pixels corresponded to CSS `innerWidth` 980. The sandbox required a local socket escalation. This does not qualify Linux, real devices, shared login or preview.
- A second desktop Chromium probe used two random `*.localhost` origins without resolver overrides. Both were secure contexts; Secure/HttpOnly host cookies, a real form POST, relative/registered absolute redirects, one WebSocket frame, Host/Origin refusal and cross-service cookie checks passed. This is not real framework HMR or Android WebView evidence. Report: `/private/tmp/pocketpi-preview-origin-report.md`.
- An isolated Android API 36/WebView 133 probe reproduced cookies, forms and WebSocket behavior, but a replacement HTTP listener received the earlier fixture cookie. Thus an HTTP localhost secure context does not authenticate the listener and is rejected for protected previews. That probe targeted SDK 28 rather than PocketPi's SDK 36. Report: `/private/tmp/pocketpi-android-preview-report.md`.
- A second Android probe targeted SDK 36 and used a hostname-scoped test CA plus static SPKI pin. HTTPS cookies, form POST and WSS worked; a replacement listener with the same CA/hostname but another key received no HTTP request. Reinstalling the test APK with the replacement pin then sent the retained fixture cookie. This proves only the static test: dynamic per-install trust, listener generations, actual ArtifactNetworkLock integration and production provisioning remain open. Report: `/private/tmp/pocketpi-android-preview-tls-report.md`.
- Capability v3 negotiation is implemented separately on app and host: missing lists fall back through v2 to v1; present malformed lists fail, and existing v2 features remain usable on v3. No browser or preview capability is advertised by this increment. The owned browser runtime is still experimental and unintegrated; real Chromium exposed that its profile singleton cannot be assumed to enforce exclusivity.
- ArtifactNetworkLock applies a process-wide dead proxy. A preview WebView must use a separately isolated rendering process with its own data directory and minimal application bootstrap; it must not initialize a second remote repository or weaken artifact policy. Main process retains pairing secrets and authorization; renderer IPC carries scoped handles and bounded chunks.
- Existing relay defaults to 512 KiB frames and 1 MiB buffered. Existing media uses 49,152-byte raw chunks and bounded snapshots. Neither is a streaming contract. Artifact CSP restricts forms, frames and connections; do not weaken or reuse that viewer for this feature.
- Use a dedicated persistent profile per trusted project under a private host state directory (0700), never the default browser profile. Shared profile and tabs are the same for agent and human. The browser and preview are separate capabilities, each off by default and enabled only by separate explicit host opt-in and project/device grant. This plan adds grant code without activating it.
- Existing pairing, trusted project resolution, device revocation and per-request authorization remain authoritative. Trust/bootstrap and local administration prompts stay local. Do not expose a CDP port, raw CDP commands, arbitrary network proxy or user browser credentials to the phone.
- Host configuration and lifecycle must work on macOS and independent Linux hosts/devcontainers. No release, activation, live-host change or physical-device qualification is implied.

## Contract and security defaults

- Negotiate `projects.list` with `capabilities.v3:<requestId>`; advertise `session.browser.v1` and `session.preview.v1` only when their independent host switches and applicable project grants permit them. v3 permits at most 64 capability strings and uses a strict parser. A new client falls back to v2 then v1 if absent; old routes keep their existing contents and limits (v1 16, v2 32, authentication 8). Never send browser commands without v3 advertisement. Existing v2 features, including terminal attach and structured keys, remain enabled on a successfully negotiated v3 route; replace equality-to-v2 guards with a shared route-revision check and test them.
- Every browser request has `requestId` and `sessionId`. Initial `browser.open` resolves the project from the session and returns a host-issued `browserId`; later requests carry that ID and, where applicable, `tabId`, `generation` and `leaseId`. IDs are opaque host-issued values. Host resolves project from the authorized session and checks the current authenticated device and grant, rather than trusting caller-supplied project identity.
- `browser.open` returns browser incarnation and current tabs; `browser.tabs` returns bounded tab IDs, titles and URLs; `browser.navigate` accepts only HTTP(S) URLs under browser network policy; `browser.close` closes an owned tab. The host creates and assigns targets. Tab creation, navigation, tab close, viewport and input require the current lease and generation; tab listing and frame subscription require current read authority. No wire command forwards arbitrary CDP methods, file URLs, browser internal URLs or host paths.
- `browser.acquire {owner, expectedGeneration}` returns `browser.lease {leaseId, generation, owner}` or `busy`. Owners are an authenticated device connection or the bound agent session. `browser.handoff {leaseId, generation, toOwner}` atomically invalidates the old lease, increments generation and grants the named eligible owner; notify both sides. Disconnect, project/session change, revocation, crash or disable revokes the lease and increments generation. Reconnect obtains a new lease and snapshot; it does not replay uncertain input.
- `browser.viewport {leaseId, generation, width, height, deviceScale, orientation}` updates an integer bounded viewport. Frames carry `browserId`, `tabId`, `generation`, `viewportRevision`, `frameId`, CSS viewport/scroll and encoded image dimensions. Input `browser.input {leaseId, generation, viewportRevision, sequence, kind, payload}` supports bounded pointer, wheel, keyboard, text and paste; reject stale generations/revisions, duplicate or out-of-order sequence, unauthorized target and oversized data immediately before delivery. `browser.input.ack {sequence, status}` is independent of frame acknowledgements; timeout is uncertain, never automatic replay.
- `browser.frame` carries JPEG bytes as bounded chunks with frame/chunk IDs and count; choose limits only after measuring encrypted JSON/base64 overhead against both relay caps, and reject a single encoded envelope above the established safe limit. Keep at most one in-flight complete frame and one newest pending frame per viewer; replace old pending frames under backpressure, expire incomplete chunks, bound reassembly and acknowledge/drop CDP frames promptly. Stop capture on detach or lease/project revocation. Frame delivery must not block input ACKs.
- Preview registration is host-local and binds project, service ID, `127.0.0.1` or `::1`, port, process/lifetime and generation. `preview.list`, `preview.open {serviceId}`, `preview.request`/`preview.response`, `preview.ws.open`/`data`/`close`, and `preview.close` carry opaque IDs, sequence numbers, bounded bytes and flow-control acknowledgements. Host rechecks device/project/grant on request and WebSocket upgrade; no client-supplied upstream address, arbitrary CONNECT, redirect following or unrestricted port forwarding. Enforce request, response, concurrent-stream and idle limits, cancellation and backpressure.
- Preview must give each service an isolated secure origin with working relative paths, redirects, cookies, forms and WebSocket upgrades without leaking credentials to another project/service. Define exact host/origin mapping, cookie rewriting, CSRF and WebSocket Origin checks during Task 1; reject unsafe or unsupported flows, never silently relax browser security. Preserve HMR and authentication only when the mapped-origin tests prove them. Do not claim universal proxy compatibility.
- Profile directories are private, owner-checked and never logged, exported or sent to devices; retain them across process restart to preserve login until the user explicitly deletes the project's browser data. Disable browser sync and remote debugging exposure. Deny permission prompts, downloads, uploads and clipboard access by default; add only separately authorized, scoped flows later. Show that rendered frames can contain secrets, avoid storing frames or sensitive URLs in logs, and provide explicit host-side profile deletion with process stopped. Protect profile at rest using OS account/state-directory controls; no claim of encryption at rest unless independently provided and verified.

## Review focus

1. A queued input arrives after handoff or revocation: reject before dispatch (Task 3).
2. Slow or disconnected receivers cannot grow frame, HTTP or WebSocket memory without bound (Tasks 3 and 4).
3. A registered service redirects or supplies a hostile Host/Origin: no unrelated service becomes reachable (Task 4).
4. Project or browser process restarts while the client holds IDs: old generations fail and uncertainty is visible (Tasks 2, 3 and 5).
5. Rotation, scaling and scrolling cannot turn a click into a click on a different target (Tasks 3 and 5).

## Staged implementation and gates

### Task 1: Freeze protocol and prove preview origin

**Files:** NOAH `packages/pi-atlas/extensions/remote/protocol/{types,parse,mod}.ts`, new `browser.ts`, `preview.ts` and `fixtures/browser-preview-v1.json`; host `daemon.ts`; PocketPi `src/main/java/de/joinnoah/pi/remote/DefaultRemoteRepository.kt` and new protocol types; matching unit tests and protocol README.

- [ ] Write cross-language valid/invalid fixture tests for all messages, strict fields, IDs, sizes, capability v3 and v2/v1 fallback. Verify old authentication/v1/v2 caps and advertisements stay unchanged.
- [ ] Define numeric caps for every message, tab list, queue, viewport, stream and timeout from measured worst-case encrypted envelope size. Record envelope measurements and compatible relay configuration before implementing frame delivery.
- [ ] Prove the Android preview process and origin with actual WebView: data-directory isolation, unchanged artifact network lock, cookies/form/WS behavior and scoped IPC. A secure-context result alone does not authenticate the local listener. Test listener/process replacement and ensure another local app cannot receive earlier credentials; select and verify origin/channel authentication without blanket certificate bypass.
- [ ] Build a throwaway local preview-origin proof for registered loopback HTTP and WebSocket services. Prove same-origin forms, cookie auth, redirects and HMR; test cross-project isolation, hostile origins, redirects to private hosts, DNS rebinding and disconnect. Select a concrete origin and cookie design only if these pass; otherwise stop preview implementation and report the failed requirement.
- [ ] Implement strict parsing and capability negotiation only after tests fail for missing support. Run focused host and Android protocol tests; independently review the frozen contract before dependent tasks.

### Task 2: Host browser lifecycle and agent access

**Files:** NOAH new `remote/host/browser-runtime.ts`, `browser-owner.ts` and tests; integrate `remote/host/{daemon,state,storage,trust-watch,project-resolve}.ts` and extension entry points using public Pi APIs.

- [ ] Test opt-in/grant defaults, trusted project/device checks, 0700 ownership, project isolation, persistent login across controlled restart, corrupted profile, target crash and denied permissions. Verify no default profile or remote CDP listener is used.
- [ ] Implement host lifecycle, process cleanup, tab registry, profile retention/deletion and agent-facing narrow operations. Bind browser incarnation to project/session authority and reject stale IDs. Browser binary discovery must fail visibly if unsupported; do not install or silently fall back to a user's browser.
- [ ] Test real browser against two agent/phone clients sharing the same tab and cookie, then process restart and reattachment; run focused host tests. The macOS probe alone is not this test.

### Task 3: Ownership, bounded frames and input

**Files:** NOAH new `remote/host/browser-stream.ts` and tests; integrate `daemon.ts`, protocol fixtures and relay tests. PocketPi new `BrowserProtocol.kt` and `BrowserRepository.kt` with tests.

- [ ] Test simultaneous acquire, explicit two-way handoff, stale epoch and sequence, disconnect/revoke during queued delivery, independent input ACK, malformed chunks and bounded memory under a stalled viewer. Test coordinate transforms at portrait/landscape, scrolling, resize and non-unit scale; stale viewport input must fail.
- [ ] Implement the lease state machine and transport described above. Capture and input use the same owned tab, and frame work cannot delay input ACKs. Recheck authority at dispatch, not just at acquisition.
- [ ] Run focused host, encrypted-relay and Android repository tests. Verify reconnection requests fresh state and never repeats uncertain input.

### Task 4: Protected project preview

**Files:** NOAH new `remote/host/preview-registry.ts`, `preview-proxy.ts` and tests; protocol files from Task 1. PocketPi new `PreviewProtocol.kt`, `PreviewRepository.kt` and integration tests.

- [ ] Test separate opt-in and grant, authorized local registration, per-request revocation, loopback-only address validation, redirect/origin/Host attacks, cross-project cookies and auth, bounded HTTP bodies, WebSocket upgrades, HMR, slow peers and service death.
- [ ] Implement the Task 1 proven mapping and bounded HTTP/WebSocket transport. Bind every stream to the registered service generation; revoke and close active streams when authorization or registration changes. Preserve WebSocket framing/order and propagate closure without replay.
- [ ] Run end-to-end tests with a real local development server exercising HTML, forms, cookie login and HMR. If the origin proof or security invariants fail, leave this capability unadvertised.

### Task 5: PocketPi interaction and qualification

**Files:** PocketPi new `BrowserScreen.kt`, `PreviewScreen.kt`, related navigation/state files under `src/main/java/de/joinnoah/pi/remote/`; corresponding `src/test` and `src/androidTest` tests. Keep `ArtifactSandbox.kt` policy unchanged.

- [ ] Test capability gating, visible owner/handoff state, tab selection, resize/rotation, touch, keyboard/mouse, reconnect, uncertain input and inaccessible service. Keep drafts and unrelated session state across view changes; display interrupted processes honestly.
- [ ] Implement adaptive phone/tablet views using the same owned tabs and explicit handoff. Expose preview separately with the proven isolated origin. Do not enable controls when host capability, grant or lease is absent.
- [ ] Run focused tests, then each repository's required checks once after integration and an independent security/diff review. Record actual results and unresolved failures.
- [ ] Qualify agent-to-phone and phone-to-agent handoff, login persistence and controlled reconnect on Mac and independent Linux/devcontainer hosts. Exercise HTTP forms/auth and WebSocket HMR on each supported configuration. Test Galaxy Tab S11 and Galaxy S25 physically, including rotation, touch, keyboard/mouse, DeX, process death and network transition. Record versions, commands and evidence; emulator/build success is not device qualification.
