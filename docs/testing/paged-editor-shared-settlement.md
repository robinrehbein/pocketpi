# Actual Pi shared editor settlement

This local qualification uses actual Pi 1.4.0 Native and managed RPC sources, the
production Host, encrypted Android transport/repository/AppStores and two distinct
paired device identities. The competing client is a fixture protocol client, not
a second physical Android device. Public paged-editor and positive-outcome gates
remain off. The full remote-development goal remains active.

## Ordered races

Both cases load the real empty source and enter exactly 1 MiB of multibyte,
escaping-heavy text through the Android windowed editor. The second client pairs,
authenticates, negotiates v3 editor capabilities, focuses the actual session,
reports native presence where required, and receives that question in a real
snapshot before it can compete. No grant, owner acknowledgement or SDK result is
fabricated.

- **Commit wins:** Android completes collection and the Host claims finalization.
  The fixture holds the real Source commit request after that claim, before Source
  dispatch. The valid competing Cancel is refused with wire `code: internal`,
  `reason: outcome_unknown`, without a Source answer or SDK result. Releasing the
  held request obtains one exact Source ACK, one SDK result and the actual accepted
  Android commit result. The encrypted attempt is removed.
- **Cancel wins:** Android has uploaded every chunk and reserved its encrypted
  attempt, but its actual guarded commit is held before transport dispatch. The
  second client successfully cancels; the original SDK returns `undefined` and
  Android observes question closure. Releasing the held guarded send fails its
  real validation callback before `possibleWrite`. No phone commit is transmitted,
  no Source commit occurs, the attempt is removed and the exact draft remains.
  This ordering tests Android's pre-write guard; it does not assert simultaneous
  Host decoding of two commit commands.

Both orders require empty Host upload quota and a three-second observation without
automatic resend. Android's monotonic clock starts before the Send action. Cleanup
and the final observation must finish within 50 seconds, before the Host's
60-second upload inactivity expiry; the zero quota cannot be attributed to that
expiry in the observed local cases. Production timeouts are unchanged.

## Recorded matrix

| Pi owner | Ordering | Case | Result |
| --- | --- | --- | --- |
| Native | Commit wins | `paged-settlement-native-commit-02` | Passed, cleanup passed |
| Native | Cancel wins | `paged-settlement-native-cancel-01` | Passed, cleanup passed |
| RPC | Commit wins | `paged-settlement-rpc-commit-01` | Passed, cleanup passed |
| RPC | Cancel wins | `paged-settlement-rpc-cancel-01` | Passed, cleanup passed |

Artifacts live under `/private/tmp/pocketpi-paged-production.oge2ybh1`: per-case
results, instrumentation logs, Android proofs, actual Source/SDK traces and 31
core-source plus APK snapshots. All four cases use identical frozen core-source
and APK pins. Cleanup observations occurred 5,278–6,131 ms after Send; the final
observations completed at 8,748–9,642 ms. The independent audit reports zero errors
with the limitations below (`paged-settlement-independent-audit.json`). A separate
root live audit confirms all eight owned Host/SDK PIDs and four Android PIDs absent
(`paged-settlement-process-exit-audit.json`). The external controller is
`/private/tmp/run-pocketpi-settlement.py`; the independent offline verifier is
`/private/tmp/verify-pocketpi-settlement.py`.

The initial Native `commit-01` failed a test expectation: it expected
`outcome_unknown` as the error code rather than the established wire reason. No
product behavior was changed for that correction. Independent review also caught
an unbounded cleanup observation; the explicit pre-expiry assertion above closes
that evidence gap. App/test APK compilation and Host TypeScript checking pass;
this increment changes fixtures only and does not rerun the full unit suite.

## Limits

The saved status samples and traces are not a complete continuous event log. RPC
Source traces alias the question ID and do not independently map that alias to
the raw Android ID. The separate live process audit establishes exit only for the recorded owned PIDs.
Actual owner replacement/revocation during transfer, other answer types and
orderings, physical Galaxy/DeX/IME behavior and independent remote Linux remain
separate gates. This matrix does not enable the public capability or establish
universal race freedom.
