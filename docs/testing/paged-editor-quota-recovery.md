# Paged editor quota recovery

This document records a scoped emulator qualification of quota refusal and explicit retry. It does not establish real-device, Samsung, or universal IME behavior. Feature gates remain off; this evidence does not authorize an external release.

## Cause and correction

The focused editor comparison failed in `paged-ime-red-01` after 8.562 seconds with the previous app APK (`f9f52…`). The real Host/Android byte-quota run first exposed a draft growing from 1,048,576 to 2,095,787 bytes after a large replacement. Reapplying a full old input value against the new trailing window explains this exact size; the focused test independently reproduced content corruption. The correction synchronously invalidates the previous field session before changing the window basis and conditionally restores focus. It preserves intentional repeated input rather than deduplicating text values. The tests exercise delayed input, an obsolete callback, subsequent cursor-position input and repeated identical input. The focused `paged-ime-green-01` run passed 11 JUnit tests and cleanup with app APK `f29415ab…`. This is evidence for the targeted input-session and focus behavior under that test, not for every IME or a physical Samsung device.

The full quick suite `65591` passed in 110 seconds: 3,642 app tests and 34 Wear tests, with zero failures, errors, or skips. No Wear emulator was used for that suite.

## Quota qualification

The host fixture fills real reservations: eight 1 MiB reservations for the byte limit, or 32 empty reservations for the slot limit. It uses actual owner and SDK results rather than a fabricated acknowledgement. The slot test's private relay permits 40 peers; the default limit remains 16.

| Owner | Limit | Status | Evidence |
| --- | --- | --- | --- |
| Native | Bytes | Passed, cleanup passed | `paged-quota-native-bytes-04`; exact 1 MiB final value. The offline auditor reported zero errors with the limitations below. |
| Native | Slots | Passed, cleanup passed | `paged-quota-native-slots-02`; 32 real empty reservations, one explicit retry, terminal reservation cleanup. |
| RPC | Bytes | Passed, cleanup passed | `paged-quota-rpc-bytes-01`; exact 1 MiB final value. |
| RPC | Slots | Passed, cleanup passed | `paged-quota-rpc-slots-01`; 32 real empty reservations. |

For `paged-quota-native-bytes-04`, the recorded quota elapsed times were 3,044 ms at full capacity, 4,009 ms at refusal, 4,251 ms after release, and 12,201 ms at commit. The observed stages were below 50 seconds, ahead of the 60-second reservation TTL. The case records a busy refusal followed by one explicit retry, an actual source acknowledgement and SDK result; it does not claim an automatic resend.

The offline auditor checks captured evidence and source/APK pins, but its zero-error result is qualified: sampled status and counters do not independently demonstrate continuous connectivity of every filler or a complete absence of replay and question-answer events. The explicit retry and accepted Android outcome depend on instrumentation proof and JUnit; a separate live process audit is needed to establish process exit. Earlier `paged-quota-native-bytes-01` and `paged-quota-native-bytes-02` attempts failed and are not passing evidence.

Artifacts are preserved under `/private/tmp/pocketpi-paged-production.oge2ybh1`: each case has its result, instrumentation log, Android proof, captured Source trace and source/APK snapshots. `/private/tmp/verify-pocketpi-quota.py` checks those independent captured files. RPC Source traces use a question alias, so they do not independently map the raw Android question ID to that alias. `paged-quota-native-slots-01` failed an incorrect assertion that inline source loading needed a page-read reply; it was corrected without changing product behavior.

All four final cases use identical current core-source and APK pins; the independent audit has zero errors with the stated evidence limitations. `paged-quota-independent-audit.json` preserves that result. The separate root process audit (`paged-quota-process-exit-audit.json`) confirms all eight owned Host/SDK PIDs and four Android test PIDs absent. No test fixture remains running; the owned emulator remains available. The earlier Native bytes `03` pass precedes a test-only correction to the inline-source assertion and is not the final frozen matrix.
