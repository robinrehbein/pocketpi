# Actual process and fresh-client recovery

## Scope

Extend the actual Application/MainActivity acceptance path, preserving real encrypted stores,
SecureRemoteTransport and native Pi identity. Keep OS task recreation, force-stop cold launch and
fresh pairing distinct. A normal cold start deliberately opens the last computer's projects;
the test must reopen the chat through real navigation rather than inject its selection.

## Process recovery

- Phase A prepares the native workflow, submits a prompt whose acceptance result and echo are
  withheld, and reaches an uncertain disconnected state. Untouched and edit-away/back cases
  use the actual composer and production draft store. Observe real Home/onStop and flush.
- An external controller captures the exact request/source receipt, native owner and app PID,
  verifies the durable encrypted journal and pairing fingerprint, then force-stops only the
  verified owned test app. Keep its data, fixture and native worker alive.
- Phase B consumes a one-use restore marker tied to A, the same fixture identity and unchanged
  pairing ciphertext with no atomic-file siblings. Bootstrap must not load, seed or replace the
  store before Application.onCreate. Open the ordinary launcher and inspect cached project/session
  rows. Explicit chat opening requires live canonical IDs because it can fork a historical session;
  preserve that guard instead of forcing an offline USER_OPEN.
- Before allowing reconnect, verify the actual restored store journal/provenance and cached title
  with no matching write attempt. Reconnect through the real UI and select the chat normally.
  Then require one exact source receipt, unchanged native owner, no repeated prompt,
  and the correct untouched/edited composer outcome persisted by the actual store.
- Qualify untouched and edited cases on phone and tablet layouts; record new app PID separately
  from stable Host/native worker identity. Do not claim spontaneous OS low-memory restoration.

## Fresh client

Use a separate optional fixture mode. After A opens a running native turn and disconnects, obtain
an unused pairing QR through the public Host pairing API. Reset only the verified owned emulator
test app. Start B with no seeded pairing, use the actual Paste Code interface and pairing protocol,
and establish a distinct authenticated device identity in a fresh encrypted store. Navigate to
the same session, recover its active output and continue work through actual UI actions.
Focus is a stream subscription, not exclusive controller ownership; browser leases are separate.
This sequential emulator test is not simultaneous use of two physical devices.

## Verification

Root owns builds, servers, ADB and cleanup. Authors and independent reviewers work on separate
source paths. Freeze source before checks/APKs, preserve failed attempts, pin source/runner/APKs
and validate structured phase proofs. Release TLS and the personal Host remain untouched.

## Result

All seven final cases pass with cleanup and independent source/runner/APK/proof review: four
force-stop cold-process cases, two fresh public Android pairing cases and the existing MainActivity
background compatibility case. The tests use identical frozen APKs. See
[process qualification](../testing/mainactivity-process-recovery-qualification.md) and
[fresh-client qualification](../testing/fresh-client-handoff-qualification.md) for exact artifacts,
failed setup attempts and scope. No production selection, pairing or ownership guard was weakened.
The overall remote-development goal remains active.
