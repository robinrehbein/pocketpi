# Fresh Android client handoff qualification

## Tested path

A uses the real Application-created repository and MainActivity to prepare a native worker turn.
Its source assistant message ID and native PID/session/runtime/project are recorded. After A ends,
the external runner captures that proof and resets only the verified owned emulator app. The
Host and native worker keep running; B's bootstrap receives no seeded pairing or A device-credential
arguments. The test fixture remains privately controlled by the runner.

B starts with an empty encrypted pairing store. The fixture issues one unused code through the
public Host pairing API, after verifying A is offline. B uses the actual Android Paste Code dialog
and SecureRemoteTransport pairing handshake. The resulting device ID is distinct from A and is
verified both in the actual encrypted PairingStore and through public Host status. The scoped
debug factory supplies only the local fixture CA and exact relay endpoint restriction, retaining
normal hostname verification. Neither the repository nor session selection is injected.

Through project/session UI navigation, B opens the running session and sees the same assistant
message containing the held prefix once. B stops that turn and sends one follow-up, on the same
native worker. The final test waits for exact field and repository draft agreement and an enabled
Send action before clicking. Host evidence records one original prompt, one abort and one follow-up;
it does not equate focus with exclusive controller ownership. Browser leases are a separate contract.

## Final evidence

Both cases pass with cleanup under `/private/tmp/pocketpi-paged-production.oge2ybh1`:

| Layout | Label | App PIDs A → B | Recorded phase duration |
| --- | --- | --- | --- |
| Phone | `fresh-client-final-phone-01` | 14820 → 14970 | 27.850 s |
| Tablet | `fresh-client-final-tablet-01` | 15275 → 15423 | 27.995 s |

Prepare proofs are captured outside the app before reset; fresh proofs compare original/new device
IDs, encrypted store identity, exact assistant ID, native owner and prompt/abort/follow-up counts.
The runner rechecks the dedicated ADB server, owned AVD, debuggable package and installed/build APK
hashes before each reset and launch. It verifies sources, runner and APK hashes after the run.
The tablet wrapper records 1600 × 2560 at 240 dpi and restoration to 1080 × 2400 at 420 dpi.

Pairing codes stay in the private HTTPS response and Android memory. Raw instrumentation output,
which may contain a pairing field in an error, is not persisted; verdicts retain only allowlisted
exception classes and source stack frames. Progress records contain draft lengths, hashes and
state/equality flags rather than text. Actual pairing ciphertext is fingerprinted, not committed.

The shared source/APK cohort is checked with the four
[actual MainActivity process-recovery cases](mainactivity-process-recovery-qualification.md).
Required checks, build logs and precise cache/execution bounds are recorded there. Host fixture
types and formatting pass; the optional mode preserves default lifecycle guards.

## Earlier attempt and boundaries

`fresh-client-phone-01` paired B and stopped the turn but timed out on the follow-up, with no exact
follow-up counted. Its evidence does not identify the cause. Candidate `-02` passed with passive
diagnostics showing that field text could precede the repository draft. The final source adds an
explicit state/Send synchronization barrier and is qualified separately above; no product or
permission fix is inferred from those test attempts.

The Host's distinct-device confirmation is a verified observation at confirmation time, not a
continuous connectivity guarantee. This is sequential use of one reset, owned API-36 emulator
with two independently paired identities, not simultaneous use of two physical devices. The
Host/provider are local and controlled; release TLS, a production relay, physical Galaxy devices,
independent Linux and browser/editor lease arbitration retain separate qualification. The overall
remote-development goal remains active.
