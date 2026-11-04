# Continuous SDK editor cleanup and release

This qualifies the original more-than-32 successful-edits criterion from the
[positive outcome contract](../plans/2026-11-01-paged-editor-positive-outcomes.md),
not the whole remote development environment. The public capabilities remain off.

The private `--paged-editor-sdk-loop-proof` fixture invokes the real Pi SDK editor
40 times in a single command, with the same Host, device, session, SDK process and
ledger. Android uses one actual repository and encrypted journal. Each unique
32-byte value (`loop-` plus a 27-digit index) is entered through the actual Compose
editor, sent over the paged transport and checked against the actual SDK return.
The next editor is gated on independent local cleanup plus actual Host evidence.
No ledger padding, expiry, per-iteration reset, fabricated ACK or injected SDK
result counts as a successful cycle.

Per-index raw evidence includes begin/chunk/commit and original Source ACK,
authenticated full outcome tuple, actual applied ledger before accepted delivery,
Android commit/result and release/result correlation, and the ledger after actual
release. Android observes completed real encrypted saves with fresh readbacks:
reservation, atomic exact-attempt removal plus release enqueue, and release queue
removal only after the correlated real ACK. The matching draft is reclaimed in
the same retirement snapshot. Final attempts, releases and drafts are all empty.
Forty distinct question/upload/commit/attempt IDs and a stable SDK PID prevent
single latest-value observations from being counted repeatedly.

## Production correction found by the test

`native-sdk-loop-01` failed at the 33rd draft save after 32 genuine SDK successes
and releases (cleanup passed). `retireApplied` retained completed drafts, reaching
the unchanged 32-draft journal bound. The new focused regression failed with the
expected assertion before the correction. Retirement now removes only a same-key
draft exactly matching the reserved attempt's text **and creation timestamp**,
in the same durable write as attempt removal and release enqueue. Changed/newer
drafts and all other keys remain; unknown/legacy attempts and failed cleanup do
not receive this positive-proof cleanup. Bounds and conservative release policy
are unchanged. This is not generic discard or capacity eviction.

## Recorded local evidence

- Native `native-sdk-loop-02`: 40 actual SDK successes and releases, 86.949 seconds,
  JUnit OK, cleanup and independent saved-evidence audit passed. Host 23737,
  actual SDK 23760 and Android 28455 were independently observed absent afterwards.
- RPC `rpc-sdk-loop-01`: 40 actual SDK successes and releases, 85.435 seconds,
  JUnit OK, cleanup and the combined independent audit passed. Host 24104,
  actual SDK 24128 and Android 28870 were independently observed absent afterwards.
  Both modes use the same APKs and 53 pinned source snapshots.
- The correction passes 22 Journal, 11 Wire and 639 Repository tests: 672 total,
  no failures/errors/skips. Both Android APK builds and Host TypeScript/format
  checks passed. The independent review covered the exact cleanup boundary and
  the final instrumentation/fixture observers.

Native pins: App APK `ce9d8b9114bdebe633011ed6c243369404b012a2e2c8e0818575d43f9f37393e`,
Android-test APK `d3333f94e661f4229bd50e9c703942ec5f607ed57183f5bcaabf831cd3781e29`;
53 source snapshots plus runner/helper and installed/build APK comparisons.
Evidence is preserved in `/private/tmp/pocketpi-paged-production.oge2ybh1`, including
raw `sdk-loop-host-trace.jsonl` and `sdk-loop-sdk-trace.jsonl` copies and independent
audit JSON. Root controller `/private/tmp/run-pocketpi-sdk-loop-v2.py` preserves the
original controller and failed run; verifier `/private/tmp/verify-pocketpi-sdk-loop.py`
checks the saved evidence without runtime access.

This proves successful count continuity for small values in the qualified local
owner modes. Existing max-value, quota, crash, query, owner-loss and wrong-authority
cases retain their own pins and scope. Actual IME composition/timing, physical
Samsung/DeX and independent Linux are not established by this run.
