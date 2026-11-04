# Actual Native wrong-grant refusal and explicit retry

`native-wrong-grant-02` passes the original Native wrong-authority criterion for
an actual invalid upload grant: real Android repository/UI and encrypted
transport, actual Host, current Native Source receiver and real Pi SDK. RPC is
not exercised or expanded. Public paged/outcome gates remain off in this slice.

The UI loads the real 1 MiB Source, edits its first visible window and restores
it through Compose value-change callbacks. The repository and encrypted journal
must contain the exact original bytes before Send. This creates a genuine saved
user draft without direct store injection; this case makes no IME claim.

Only the first actual upload.begin grantId is changed by the private transport;
all other fields, request ID and beforePossibleWrite guard are forwarded
unchanged. The Host's actual correlated response is `forbidden`. An immutable
snapshot at that response records zero Source upload begin/chunk/commit, zero
SDK results, a still-pending Native question, verified owner and zero original
local editor calls. Earlier Source reads are legitimate and are not counted as
upload dispatch. The existing Host path rejects the unresolved grant before
allocating the upload.

Android preserves the same question object (assertSame), connection and exact
encrypted draft, with no attempt/release or uncertainty fence. A three-second
observation confirms no automatic retry. Only the second explicit UI Send carries
the original valid grant. It completes one Source begin, 22 chunks, one commit,
actual matching Source ACK and exactly one SDK value with the original 1 MiB
hash. Phone totals are two begins, 22 chunks and one commit, with no direct
question.answer bypass. Raw Source transfer IDs are separately correlated with
the actual begin/ACK/commit/ACK and are not confused with Android upload IDs.

## Evidence and scope

- App baseline `35b295e7`, Host baseline `1423a6679`; both clean/up-to-date by
  Git-first checks. Production guards and protocol behaviour are unchanged.
- Final Android build `native-grant-build-02.log` passed; Host TypeScript
  `native-grant-tsc-02.log`, formatting, diff and independent scoped review passed.
- Actual Native `native-wrong-grant-02`: JUnit OK, 31.884 seconds, cleanup passed.
- Independent `/private/tmp/verify-pocketpi-native-wrong-grant.py` passed against
  the saved wire/ACK, immutable refusal, journal assertions, raw Source and SDK
  trace, 52 source snapshots and installed/build APK/controller/helper pins.
- App APK: `05b77d897b307996b1ab720137f33b2ae4fafc4687684b8c93c3124964e3ceb3`.
- Test APK: `32a578f688f370af0990b53390ad205652a39db56b4b6fa45f2d127c3c4e3c9e`.
- Host 66846, actual SDK 66866 and Android 1917 were independently observed absent
  afterwards. No personal Host, default ADB or external service was touched.

Saved evidence is in `/private/tmp/pocketpi-paged-production.oge2ybh1`, including
`native-wrong-grant-02-independent-audit.txt`. Root controller is
`/private/tmp/run-pocketpi-native-wrong-grant.py`. Failed attempt 01 remains failed:
the actual refusal and retry reached the final SDK proof, but the test requested
a missing sourceSdkResultCount field. The private fixture now exposes the actual
trace-derived counter; no result was fabricated or accepted without repeating
the full run.

Object identity and encrypted-store observations depend on the pinned successful
instrumentation assertions; serialization alone cannot prove pointer identity.
This one invalid-grant case is not every wrong-device/lease/replacement ordering
or physical-device acceptance. Existing Native replacement, revocation, receipt,
quota and lifecycle evidence retains its separate scope. Native production
negotiation/wiring is the next reviewed step; frozen RPC compatibility adds no
parallel release gate.
