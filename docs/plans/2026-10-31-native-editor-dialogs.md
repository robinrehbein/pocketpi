# Native editor dialogs
Implement Task 5 of the remote-editor plan; full remote-development scope remains unchanged.

- [x] Qualify the public native input mechanism with actual isolated Pi PTYs and independent review.
- [x] Add explicit rendered-chat presence, capability negotiation and bounded connection/session leases; never infer visibility from stream focus.
- [x] Implement the source-owned deferred native editor and immediate key-driven local handoff, preserving original arguments and lifecycle.
- [x] Integrate Android presence renewal/release and Host source/answer/delivery authority.
- [x] Complete negative/race tests and independent reviews.
- [x] Qualify actual Pi native editor Submit/Cancel, local fallback and reconnect/client handoff; activate the bounded native editor. The second handoff client is a protocol client, not another Android UI.

The binding implementation contract is [native editor contract](2026-10-31-native-editor-contract.md).
Public Pi 1.4 editor has no AbortSignal. A queued slash command cannot be used as the local escape.
Native local UI must never open before remote authority is revoked. Do not patch Pi core.
The SDK-only probe passed; it does not qualify real Host leases or Android behavior.
Oversized/paged prefill remains the next separate requirement.

## Qualification result (1 November 2026)

The bounded native bridge is enabled by default after actual Pi 1.4.0 and Android Submit, Cancel, local fallback, pending-editor reconnect and distinct encrypted-client handoff qualification. Independent review accepted the actual grant-to-owner proof and completed matching registry forget before the original local editor invocation. An explicit false Host override remains available. See [native editor qualification](../testing/native-editor-qualification.md) for exact evidence, the two failed fixture runs and their corrections, skipped tests, and limits.

The app quick check passed with 3,386 app and 34 Wear tests. Source/runtime coverage passed 47 tests with 6 skipped; default/authority covering tests passed 128. The broader Remote run retained one outdated RPC-test failure; its test-only correction passed 88 local-client tests, without claiming a repeated green full run. Owned processes, ports and temporary credentials were cleaned up.

Physical Samsung devices, a second Android UI and an independent Linux host remain unqualified. The oversized/paged editor and broader remote-development goal remain open; the [next implementation plan](2026-11-01-paged-editor-dialogs.md) preserves a mandatory full-value final-hop gate for every advertised owner.
