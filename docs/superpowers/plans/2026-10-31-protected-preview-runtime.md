# Protected host preview runtime implementation plan

**Goal:** Run a separate project/endpoint-scoped preview browser that supports ordinary dev HTTP, POST and WebSockets while routing its network through an exact-endpoint proxy.

**Architecture:** A dedicated host Chromium profile renders the preview; existing private CDP pipes, screenshots and bounded input remain usable by the mobile client. All HTTP/CONNECT traffic goes through a disposable loopback proxy fixed to one numeric 127.0.0.1 port. Trusted launch options remove implicit loopback bypass, block DNS resolution outside the proxy address and disable non-proxied WebRTC UDP and QUIC. This is an empirically qualified browser-network policy, not an OS firewall or a browser-exploit sandbox. The general owned browser remains separate and unchanged by default.

**Decision:** The API 36 isolated Android service/context did not construct WebView, while a controlled host-renderer spike passed HTTP/POST/WS and showed denied foreign HTTP and no STUN UDP with a positive control. A host renderer preserves ordinary development-server semantics and avoids putting pairing secrets or network-enabled WebViews into the artifact process. This is the builder's implementation choice under the user's execution authorization. No new HTTP tunnel is needed between the renderer and Android when rendered frames/input use the existing channel; dev HTTP and WS stay on the host.

**Spec:** `docs/remote-development-goal.md`, `docs/testing/protected-preview-foundation.md`.

## Constraints

- Do not advertise a preview capability until daemon, local-owner grants and app integration work.
- Do not reuse browser grants or relax ArtifactSandbox, ArtifactNetworkLock or terminal WebViews.
- A local owner grant is exact device/project/port; the stable caller guard must revalidate project/session/device/grant lifetime. Proxy authorization is checked at connection and actual byte-forwarding boundaries and periodically for idle connections.
- No remote URL, DNS name, arbitrary proxy, exposed CDP port, credential logging, --no-sandbox or --ignore-certificate-errors.
- Private profiles are distinct from general browser and other endpoints. Revocation/failure closes sockets and browser; failed child termination retains existing profile-lock safety.
- Bounded concurrency, headers, tunnel byte totals and idle time; Node backpressure must remain effective.

## Tasks

- [x] `host/preview-proxy.ts` + tests: fixed literal endpoint HTTP and CONNECT proxy; deny wrong hosts/ports, malformed authorities, proxy credentials, upgrades outside CONNECT and unsafe framing; retain regular app authentication/cookies only to the same endpoint; never follow redirects itself. Test real HTTP/WS, binary/backpressure, revoke during forwarding, connection errors and cleanup. Root observes RED before production.
- [x] `host/browser-runtime.ts` + tests: optional trusted numeric local-proxy launch mode, fixed safety flags and strict validation; default general browser launch unchanged. Root verifies RED, green and native Node import/check.
- [x] `host/preview-runtime.ts` + tests: own proxy/profile/browser lifecycle, stable guard, exact endpoint namespaces, fail-closed startup/revocation/shutdown and real controlled dev-server HTTP/POST/WS/frame/input. Include denial of foreign loopback and controlled WebRTC UDP with a working positive control.
- [x] Independent review, relevant host checks and reproducible local evidence; commit locally without capability/production activation.

## Remaining full-preview integration

Bind the runtime to current authorized sessions and PreviewPolicy, add explicit local grant/revoke commands, advertise only while genuinely served, and add a separate preview pane with frame/input handling. Prove encrypted Android phone/tablet workflows, HMR, profile/cookie isolation, revoke and reconnect, then Mac/Linux/devcontainer and physical Samsung gates. These are not satisfied by component tests alone.
