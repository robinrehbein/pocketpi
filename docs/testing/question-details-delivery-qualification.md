# Question details delivery: current qualification

The full paginated phone approval flow is still under implementation. No question-details or
editor capability is advertised by this increment.

## Implemented and tested

Host commit `1f4163144` fixes the existing non-TUI approval renderer. It masks recognized secret
patterns in the full title/body, requires raw and masked text to fit the existing 4,096/16,384
UTF-8-byte limits, and never approves from a cut call/reason or after abort. Oversized contexts
remain unavailable until paginated delivery exists. Local TUI rendering is unchanged. Shared
masking now handles complete and unterminated long PEM blocks without leaking the suffix,
preserves matching END markers and subsequent commands across repeated masking, and only treats
an exact mask marker as already redacted. This is the existing heuristic policy, not universal
secret detection. Regression tests include the actual tool_call-to-callSummary-to-RPC-confirm path.

Host commit `5ea921b60` adds source-owned immutable memory documents and per-connection delivery
tracking. Documents are at most 1 MiB, pages at most 49,152 raw bytes; store capacity is 32 documents
and 8 MiB, with refusal rather than silent eviction. The tracker binds exact source/question
objects and descriptor values; only onSent advances hash-checked sequential delivery. Dropped,
parallel, out-of-order, foreign, stale and corrupt pages cannot grant completion. Invalid
rebinding preserves existing delivery, and hash failure allows a fresh download from zero.

Root observed RED failures before both implementations and before each review correction. The
final covering run passed 416 tests across 18 files (claude-settings plus Remote approvals,
dialogs and details). Extension type checking and scoped formatting passed. Independent reviews
approved both corrected scopes. The earlier App merge passed 3,339 Android plus 34 Wear tests;
no Android details-download UI is implemented by these commits.

## Remaining integration gates

Producer documents must contain complete, safely masked decision context and be tied to exact
pending approval/question identity. Native and RPC source/lifecycle handling, trusted Host-to-owner
attestation, per-connection read/delivery grants, close/revoke/reconnect invalidation, legacy
snapshot/event/replay projection and bounded v3 negotiation remain required. Android still needs
strict sequential page validation, SHA-256/UTF-8 verification, full-text display and independent
Yes gates in UI/repository/background entry points. Combined actual producer-to-Android tests
must pass before capability advertisement. Editors are separate work. Physical Samsung devices,
an independent Linux testhost and live production qualification remain unavailable/open.
