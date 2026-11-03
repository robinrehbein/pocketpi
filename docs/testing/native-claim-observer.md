# Native claim observer: argument preservation and actual profile evidence

The private runtime fixture previously called the real native lease `claim` with
only its first three arguments. Its optional fourth `{ paged: true }` argument was
lost. The Host production registry was correct, but large-prefill runtime tests
using that observer did not exercise the paged-specific reader/coverage rules.
Historical protocol, bytes and process observations retain their recorded scope;
they must not be promoted to proof of paged-specific lease coverage.

An empty initial Source editor normally uses an unpaged lease even if Android
later expands its input to 1 MiB. That is intentional and distinct from an
already large Source prefill. The wrapper defect does not turn those empty-source
runs into failed tests, and a large final upload alone does not prove a paged
Source claim. The first profile-audit attempt incorrectly expected a paged claim
for the empty-source case; its assertion was corrected to reflect the real
profile, rather than changing the trace or the product.

## Regression and correction

The fixture now uses the small private `observeNativeClaim` helper, whose normal
path forwards the complete typed argument tuple and returns the exact registry
result object. A focused test runs the real registry through that same helper:
with only an ordinary reader, an unpaged claim is available while a paged claim
is refused; with a paged reader the paged claim and its authorized proof become
available. An ordinary reader cannot use that proof, and existing claims cannot
silently change profile. The test also checks observed arguments and result
identity. It first failed by granting a paged claim without an eligible reader,
then passed after full-argument forwarding.

All 46 tests across `native-claim-observer_test.ts`, `native-editor-leases_test.ts`
and `native-editor-paged_test.ts` pass, as do the extension TypeScript and scoped
format checks. A bounded read-only audit found no other dropped arguments in the
adjacent presence/proof/release/forget wrappers. Independent review confirmed
normal argument/result preservation. This does not claim exception isolation or
protection from arbitrary observer mutation: the actual observer does not mutate
the result, but callback exceptions can still propagate.

## Actual Native / Android checks

| Case | Actual Source claim | Result | Duration |
| --- | --- | --- | --- |
| `paged-resume-native-profile-01` | Empty prefill, two unpaged claims | Same-ID owner resumption passed, cleanup passed | 36.484 s |
| `native-paged-profile-draft-01` | 1-MiB prefill, paged claim | Android process recovery passed, cleanup passed | 46.227 s |

The first repeats [same-session resumption](paged-editor-same-session-resume.md)
with the corrected observer and 50 source pins. Its authority trace has exactly
the expected old/fresh question IDs and `paged: false`, consistent with the empty
Source prefill. The separate same-session audit reports zero errors and retains
its documented raw-header/receipt and bounded-wire limitations.

The second uses 32 source pins and forces the first actual Android process to
stop after saving its local draft, before any remote upload begin or commit.
The second process accepts an authoritative snapshot containing the same Source
question, receives a fresh grant and registers a second reader. Reading/restoring
sends no upload or answer. Only the later explicit action produces one begin,
22 chunks and one commit; the actual Native SDK returns exactly 1,048,576 bytes
with the independently matched final hash
`900dce5cc5d1bc1b5f209c09f600c445e85149e116e2b87542913b07d06f1763`.
Its real authority trace records `paged: true`. Combined with the behavior test
through the actual helper, this demonstrates forwarding and actual paged-profile
use, not just a logged input flag.

Both cases use app APK
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`
and test APK
`dadddbfd708bbc56d2908208fe0a82548536b5b5370d7d19489b9c2429689b35`.
Android production/test sources are unchanged in this increment; no new full App
unit run is claimed. Artifacts are under
`/private/tmp/pocketpi-paged-production.oge2ybh1`.
`native-claim-observer-final-independent-audit.json` checks current source and
runner snapshots, both authority profiles, process-recovery proof and SDK hash,
and separately confirms all five owned Host/Pi PIDs and three Android PIDs
absent, with the owned emulator idle. Raw epoch/receipt observations retain the
original fixture's limits; this is not physical Galaxy acceptance.

## Next boundary

This corrects the qualification foundation. Actual Ctrl-] local takeover during
a held upload ACK, lease revocation while the worker stays alive, same-question
reacquisition and the other public enablement gates remain open. The concrete
[local takeover plan](../superpowers/plans/2026-11-02-native-editor-local-takeover.md)
distinguishes local question closure from a silent Host-only lease revocation.
Public paged/outcome gates remain off; no release or production host changed.

The subsequent [local takeover case](native-editor-local-takeover.md) exercises
a real paged-lease revocation during the held upload with that worker still alive.
Its limits remain distinct from silent Host-only revocation and reacquisition.
