# Actual Pi process loss before editor commit

This qualification targets actual Native and managed RPC Pi 1.4.0 processes,
production Host transfer/authority checks and the Android encrypted repository.
The public paged-editor and positive-outcome gates remain off; the full remote
Android development goal remains active.

## Boundary and expected behavior

Android loads the actual empty Source, enters exactly 1 MiB of multibyte,
escaping-heavy text and submits through the normal guarded transport. It persists
its exact attempt before the phone commit can be sent. The fixture receives and
validates the actual Source's last chunk acknowledgement, then holds that same
reply before the Host's next authority check. It does not fabricate a reply or
change a grant, question or owner map.

The fixture revalidates the Source PID, session, question and retained process
handle before stopping that owned worker. Native abrupt loss signals the verified
PTY handle with SIGKILL. RPC abrupt loss signals the verified RpcProcess's own
child handle with SIGKILL and awaits its real exit. RPC graceful loss uses the
existing RpcProcess.close path. Neither path sends a signal using an arbitrary PID.
The fixture waits for the original peer's real IPC disconnect and invalidation of
the captured owner binding, then returns the unchanged real acknowledgement.

The production Host must reject before Source commit. The Android commit was
already sent, so Android must retain its exact encrypted attempt as uncertain and
keep the exact draft; this test does not turn question absence or a generic error
into a safe retry. It requires a correlated negative phone result, no accepted
commit and no automatic resend during a three-second observation. Host upload
quota must return to zero before the inactivity expiry.

Abrupt termination requires no SDK return. Graceful RPC termination instead
requires exactly one cancellation return (`undefined`): actual nonce, historical
owner PID, session, RPC mode and null byte/hash fields must match. Neither case
permits a positive SDK value or Source commit. The Android clock starts before
Send and the Host clock before Source begin; both final observations must be
under 50 seconds, ahead of the 60-second upload inactivity expiry.

## Matrix

| Source | Termination | Case | Result |
| --- | --- | --- | --- |
| Native | Abrupt | `paged-owner-loss-native-abrupt-03` | Passed, cleanup passed |
| RPC | Abrupt | `paged-owner-loss-rpc-abrupt-02` | Passed, cleanup passed |
| RPC | Graceful | `paged-owner-loss-rpc-graceful-02` | Passed, cleanup passed |

Artifacts are preserved under `/private/tmp/pocketpi-paged-production.oge2ybh1`:
per-case results, Android proofs, instrumentation logs, actual Source traces and
31 core-source plus app/test APK snapshots. All three final cases have identical
frozen core-source and APK pins. Cleanup was observed 5,024–5,148 ms after Send;
final Android observations finished at 9,227–9,251 ms. All three actual phone
results were negative with code `forbidden`; the exact correlated results are
preserved rather than treated as proof that automatic retry is safe. The
independent audit has zero errors with the limits below
(`paged-owner-loss-independent-audit.json`). A separate root live audit confirms
six owned Host/SDK PIDs and three Android PIDs absent
(`paged-owner-loss-process-exit-audit.json`). The external controller is
`/private/tmp/run-pocketpi-owner-loss.py`; the offline verifier is
`/private/tmp/verify-pocketpi-owner-loss.py`. App/test compilation, Host TypeScript
and scoped formatting pass. This increment changes test fixtures only and does
not repeat the full unit suite.

The original Native `paged-owner-loss-native-01` reported JUnit success but its
controller failed while saving its final report, so it is not counted as a full
pass. `paged-owner-loss-native-02` passed before the final termination-kind
extension. The original `paged-owner-loss-rpc-01` failed an incorrect test
expectation: graceful RPC shutdown returned a valid SDK cancellation rather than
no SDK result. The final matrix preserves both graceful and abrupt cases instead
of discarding that behavior.

## Limits

This is Source process/IPC loss before Source commit, not a live replacement with
the same question ID, lease-only revocation or receipt recovery after a possible
Source write. Samples do not prove general absence of other owners or automatic
restart behavior. Native authority trace correlation is available; RPC Source
traces use aliases and raw question correlation relies on the pinned fixture.
An offline audit does not establish process exit independently; root live process
checks are separate. Physical Samsung/DeX, wider IME and independent remote Linux
remain unqualified. Native graceful termination is not included in this matrix.
