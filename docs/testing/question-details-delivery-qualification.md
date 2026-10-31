# Question details delivery: current qualification

Producer, Host and Android integration are implemented and independently reviewed. The Host
enables confirm details by default (`?? true`), honors explicit false, and still requires per-connection
opt-in. Broad final verification and hardware/Linux qualification remain open; editor support is separate.

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

## Integration evidence and open gates

Tasks 2–4 and their independent review fixes are complete and approved. Focused runs passed:
producer 78 tests in 3 files, protocol 72 tests in 3 files, Host 10 tests in 3 files, and Android
567 tests in 5 classes. The latest forbidden-recovery test and APK assemblies also passed. These
are scoped results; the final combined suite remains pending.

Four fresh default-configured actual Pi-to-Android runs passed, each JUnit OK (1 test):

| Scenario | Time |
|---|---:|
| Native `confirmPhoneEN` | 12.462 s |
| Native `approvalTabletDE` | 15.183 s |
| RPC `approvalTabletDE` | 11.912 s |
| RPC `confirmPhoneEN` | 12.261 s |

All reported the expected verification, answer and acceptance fields, one cut and two upgrades;
the native runs also reported input, native and owner as true. Approval local UI call counts were
one for native and zero for RPC. The earlier four runs also passed (15.065, 15.779, 13.627 and
12.366 s respectively). The actual public Pi was version 1.4.0; no model, tool or GitHub was used.
RPC ran through an external RPC process, not the Host-managed fallback. Eight PNGs were captured
for the default cases; native phone and tablet loaded views were inspected and legible. Tests
asserted full-string equality, which does not establish that a user read every line.

TypeScript extension type checking passed; Biome passed 24 files without warnings. Activation tests
passed 12 tests in 4 files before the additional denial coverage. The full Android/Wear run passed in 1 minute 32 seconds: 3,356 Android tests in 251 classes
and 34 Wear tests in 4 classes, with no failures, errors or skips. The broad Remote/claude-settings
run remains pending. An old golden expectation was corrected to require the complete context; the
whole-index file then passed all 147 tests in 4.71 seconds, and the correction passed independent review.

Root closed all owned fixtures and the owned emulator, stopped its ADB on 5191, verified ports 5191,
5584 and 5585 closed, cleared fixture metadata and removed 16 device JSON/CA copies. The personal
ADB on 5037 and Host were untouched.

The historical German tablet run failed after 68.561 s waiting for the native input trace. Its
cause remains unexplained, even though subsequent runs passed; it is not reclassified as a pass.
Physical devices and SSH are unavailable. Independent Linux-host and production qualification
remain open. The final broad test results are still required before claiming qualification complete.
