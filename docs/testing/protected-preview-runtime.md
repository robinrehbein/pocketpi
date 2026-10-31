# Protected host preview runtime

## Local qualification, 2026-10-31

Host local commit `d976c43bc`, based on `5341d14ae`, implements an endpoint-scoped preview runtime.
A separate Chromium profile renders an explicitly selected project/port; ordinary development HTTP
and WebSocket traffic stays on the host. The existing browser handle exposes image capture and
bounded input so the mobile integration can reuse the encrypted frame/input channel. This avoids
adding a network-enabled WebView to the artifact/terminal process. The failed Android isolated-service
probe and the original policy foundation remain documented separately.

This is not yet a user-facing preview feature: daemon/session/PreviewPolicy wiring, local-owner grant
commands, capability discovery, a separate app pane and encrypted Android qualification remain open.
No preview capability has been advertised, no grant enabled, and no general-browser grant reused.

## Runtime boundary

Each endpoint gets a private profile, separate from the general owned browser and other preview
ports. The runtime fixes proxy destinations to literal `127.0.0.1` plus one numeric port. HTTP and
CONNECT authorities, Host headers, framing and proxy credentials are validated; the proxy does not
follow redirects itself. A subsequent foreign destination is denied. Application cookies and
Authorization are preserved only to the fixed endpoint. HTTP and CONNECT streams use Node
backpressure with bounded client/request concurrency, headers, byte totals and idle duration.
Client cancellation closes its pending target request. Guards run before forwarding writes and at
idle revalidation; proxy revocation tears down the browser and streams.

The supplied stable guard must check current project/session/device/grant lifetime; this runtime
is not itself a substitute for daemon authorization. A different guard cannot adopt a live binding.
Up to 16 pending or live bindings are counted. Teardown stops the proxy immediately, including while
browser startup is pending. Unexpected browser exit triggers cleanup through a logical close observer.
If startup or termination leaves a child alive, the binding stays counted and reports a local
inspection/restart error; existing profile-lock and private-temporary-directory protections remain.
Logical close is not a promise that an unkillable child has died.

Trusted preview launch settings remove implicit loopback proxy bypass, constrain host resolution,
disable QUIC and non-proxied WebRTC UDP, and preserve Chromium's sandbox/private CDP pipe. Both
`--force-webrtc-ip-handling-policy` and `--webrtc-ip-handling-policy` are set to
`disable_non_proxied_udp`: the tested Headless Shell and Full Chromium variants require different
switches. The ordinary browser's default launch remains unchanged. There is no arbitrary-flags API,
public CDP listener, `--no-sandbox` or certificate-error bypass.

These settings and tests qualify the observed browser network paths, not an OS firewall, protection
against a browser exploit, or every possible DNS/UDP/TCP mechanism. The Linux outer seccomp profile
is still an experimental Moby-derived policy; production packaging and intended-host review remain
open. Development servers with hard-coded alternate origins or ports are denied by design; HTTPS
certificate trust and complete framework-specific HMR compatibility are not qualified here.

## Actual checks

The real Chromium integration loads a controlled development page, echoes a WebSocket message with
an HMR-like payload, sends a real POST through text input, and captures a 411-pixel-wide frame.
It proves cookies do not cross into another preview port or the general browser profile, a foreign
loopback server receives no request including a redirect attempt, and a controlled STUN UDP server
receives zero preview packets while the general-browser positive control sends packets.
This is real HTTP/WS behavior with a deterministic local page, not a full Vite/Next HMR workflow.

- Final Mac checks: **47 passed** across browser, proxy, lifecycle and real integration files;
  `/private/tmp/protected-preview-mac-final-focused.log`.
- Final Linux checks: **49 passed**, adding two actual Pi worker tests;
  `/private/tmp/pocketpi-linux-environment/root-protected-preview-final-tests.log`.
- Full remote suite before the final second-WebRTC-switch correction: **3,055 passed, 23 skipped**;
  `/private/tmp/protected-preview-final-host-suite.log`. All affected paths were rechecked in the
  final 47/49 runs; this is not a fresh full-suite result after that final constant change.
- Final extension/type and repository format checks passed; 92 existing warnings and three infos;
  `/private/tmp/protected-preview-qualified-final-check.log` and `*-final-format.log`.
- Independent review prompted two lifecycle regressions and corrections. Final round 3/3 found no
  further concrete blocker. Root verified the bound artifact before committing.

The Mac screenshot `/private/tmp/pocketpi-protected-preview-mac.jpg` was visually inspected; the
final launch variant also saved `pocketpi-protected-preview-mac-final.jpg`.

The first Linux run used `--network none`; its unprotected STUN control could not send, so its zero
measurement was inconclusive. A disposable internal Docker network then exposed three actual
preview UDP packets with only the Headless Shell switch. Adding the Full Chromium switch fixed this
without weakening the assertion. The final run retained all capabilities dropped,
`no-new-privileges`, the bounded seccomp profile, read-only source overlays and no published ports;
the container root remained writable. The internal test network was removed automatically. These
are local Linux/arm64 Docker results, not an independent remote host or production devcontainer.

## Next integration

Bind the runtime to session trust and exact-port PreviewPolicy grants, expose owner enable/grant/revoke
commands, and connect a separate preview pane to the existing frame/input transport. Then prove
actual encrypted phone/tablet flows, framework HMR, cookie separation, active revoke and reconnect.
Keep artifact/terminal WebViews locked. Physical Galaxy S25/Tab S11, independent remote Linux,
devcontainer, emulator streams and complete remote development workflow gates remain open.
