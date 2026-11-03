# Protected preview foundation implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the independent units below. Root owns builds and integration.

**Goal:** Establish the exact endpoint authorization boundary for protected HTTP/WebSocket previews and resolve the Android renderer feasibility gate.

**Architecture:** Preview authorization is independent of owned-browser control. The local host owner grants a specific device, project and numeric loopback port. Every request uses a fixed endpoint and normal current session/project/actor authorization; no client-supplied host, DNS lookup or URL credential. A renderer must not inherit the existing artifact process's permanent dead proxy or gain arbitrary network access.

**Tech Stack:** TypeScript/Node 24 host, existing Vitest, Android Kotlin/SDK, Chromium on Linux.

**Spec:** `docs/remote-development-goal.md`; existing `docs/testing/browser-context-retention.md` and `linux-host-qualification.md` define already qualified subsets.

## Global constraints

- Preserve the full remote-development goal; this foundation does not advertise a working preview capability.
- Do not reuse browser grants, modify Pi core, relax ArtifactSandbox/ArtifactNetworkLock or enable a preview renderer before its network boundary is proven.
- Main app alone owns pairing secrets. Preview capabilities are revocable and bound to device/project/session/current connection.
- Host endpoint is literal 127.0.0.1 plus an integer port 1–65535. Grants are explicit local owner decisions, never inferred from discovery.
- No deployment, shared settings, external publication, or physical device claim.

## Review focus

- Malformed or duplicate grants must fail closed, not preserve a valid subset.
- Failed persistence must not make an unpersisted grant effective.
- Grant mutation must be serialized or explicitly reject overlap without stale overwrite.
- Loopback addresses and localhost ports include sensitive services: grant matching must include the exact port.
- Renderer profile separation is not a firewall; prove the isolation mechanism before selecting it.

## Tasks

- [x] Add `host/preview-policy.ts` with its own `previewControl` and `previewGrants` configuration namespace, exact device/project/port matching, strict whole-config validation, bounded grants and transactional effective state. Add negative, persistence and concurrency tests; observe RED before implementation.
- [x] Probe WebView initialization and document loading in a separate debug-only APK's isolated service versus a normal service. Record UID/permission and structured success/error results. Keep it outside the product checkout until the approach is qualified.
- [x] Replace Linux diagnostic unconfined seccomp with the smallest empirically justified bounded profile, or record a precise remaining runtime constraint. Use only isolated disposable containers and existing dependencies.
- [x] Independently review, run relevant host checks, commit qualified source and record exact proof boundaries.

## Following implementation

After the renderer decision, add bounded HTTP and WebSocket streaming over the authenticated encrypted transport, with per-chunk authorization, backpressure, cancellation, revocation, header/origin/cookie policy and no network replay. Wire host-owner grant commands, daemon lifecycle, protocol parsing and capability discovery only with a working service. Then integrate preview UI and prove HTTP/fetch POST/WS/HMR, cookie/storage separation, denied external endpoints, both artifact/preview startup orders and teardown on revoke/process loss. None of these gates is satisfied merely by the policy unit or a renderer creation probe.
