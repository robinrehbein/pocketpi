# Saved editor access and continued work after Pi loss

PocketPi now exposes its saved editor text from the computer list, including while
offline. Drafts and uncertain submissions are separate entries: a newer draft is
not substituted for the exact previously submitted value. Loading, viewing,
copying a section and exporting never clear or settle the encrypted attempt.
Storage failure is shown as a failure, not as an empty journal.

The viewer renders at most 2,048 UTF-16 units in a preview capped at 240 dp with
its own scrolling. First/previous/next/last and section selection stay synchronized.
Phone uses list then detail; a wide tablet uses both panes. Full text is not put
in the semantics tree, clipboard or saved-instance Bundle. Only the visible
section is copied to the clipboard.

Export is an explicit Android document-picker action. Valid Unicode is streamed
as UTF-8 `.txt`; invalid surrogate code units are preserved as BOM plus original
UTF-16LE data in `.utf16`. Output uses bounded writes, and the UTF-8 encoder rejects
invalid input instead of silently replacing it. The pending export holds an
immutable selected entry; cancellation, failed writes or lost pending state do
not clear the journal or report success.

## Checks and evidence

Artifacts are under `/private/tmp/pocketpi-paged-production.oge2ybh1`.

- Nine focused tests pass: four repository/API cases and five export/pending-entry
  cases. The repository cases prove offline reads, separate draft/attempt content,
  preserved foreign scopes/releases, failure versus empty, and cancellation.
- The original missing API/export/UI tests were observed failing before their
  implementation. Further observed regressions cover unbuffered raw-data writes,
  silent UTF-8 replacement, stale section numbering and the oversized preview.
- Phone and tablet UI tests pass in their recorded emulator scope; screenshots
  were inspected. Tablet emulation uses 1,600 × 2,560 at 240 dpi and restores the
  original 1,080 × 2,400 at 420 dpi afterward. This is not a physical Galaxy claim.
- The real Android document-provider test reads the actual returned URI and
  independently checks 1,048,576 UTF-8 bytes and 2,097,154 raw UTF-16LE bytes. It
  validates the unique document name and deletes only its created document.
  The external controller independently checks the captured bytes and absence of
  its nonce-qualified destinations. This export scenario uses constructed
  immutable entries; the Pi recovery scenario below reads the real encrypted
  journal. They are separate tests, not one combined export-to-Pi claim.

## Actual normal reopen

The public phone operation `sessions.open` intentionally forks a saved offline
session into a new session ID and file. The scenario navigates back to the actual
offline/history row and taps it; it does not directly create a replacement or
modify a session/owner map.

After the proven old-owner loss before Source commit, the test preserves the old
1-MiB draft and uncertain attempt. It verifies the actual fork, distinct new
process, registered Source bridge and new native lease where applicable. Through
the UI it sends a fresh probe and a different explicit 32-byte editor value. The
actual new Source receipt, SDK result and accepted Android result must match the
new session, process, question, request, nonce and hash. The old attempt remains
unchanged, its commit is not replayed, and the local recovery UI shows its bounded
first/last sections alongside the separate saved draft.

The old-loss cleanup and observation are captured before reopening, under the
50-second bound ahead of the 60-second reservation expiry. The subsequent normal
user workflow has no artificial 50-second deadline; the Host clock keeps running.

| Final case | Scope | Result |
| --- | --- | --- |
| `paged-reopen-native-05` | Actual old/new Native Pi owners, real Android UI/journal | Passed, cleanup passed |
| `paged-reopen-rpc-02` | Actual old/new RPC Pi owners, real Android UI/journal | Passed, cleanup passed |
| `editor-recovery-phone-final-01` | Two local recovery UI cases | Passed, cleanup passed |
| `editor-recovery-tablet-final-01` | Same cases in wide emulation | Passed, cleanup passed |
| `editor-recovery-saf-04` | Real document provider, exact UTF-8 and raw-data copies | Passed, cleanup passed |

Earlier `paged-reopen-native-04`, `paged-reopen-rpc-01` and
`editor-recovery-saf-03` passed before a terminology-only resource change. The final
matrix above is repeated on the current resource/source pins. Earlier diagnostic
Native runs failed fixture assumptions about RPC-only registration flags, omitted
project-root working-directory fields, or observing only unguarded sends; those
are not passing qualification cases. The first broad quick-check found six new
messages violating the existing glossary; they now use computer/Rechner and the
focused glossary check passes.

The final quick-check passes in 85 seconds: 3,651 app and 34 Wear tests, zero
failures, errors or skips. Seven emulator instrumentation tests pass across the
five final runs above. The actual Pi runs finish in 34.531/34.948 seconds and
preserve 48 current core/test/resource pins each; all final runs use app APK
`604fe073989a26cd71d37a3da6b7ddef2486dac2f83ee01491de03ec07214b57` and test APK
`dcefe57586b2285b621871efc2b25fb48af3802c6741beedd907fca3c03fad94`.

`paged-reopen-final-independent-audit.json` reports zero errors with its stated
limits. `paged-reopen-final-process-exit-audit.json` separately confirms all six
owned Host/old/new Pi PIDs and both Android E2E PIDs absent. The final SAF case
independently matches the captured UTF-8/raw bytes and current source snapshots;
its own documents are removed. Final phone/tablet screenshots were inspected and
the display restore record matches the original geometry. No runtime fixture
remains running; the owned emulator is idle.

## Limits

Public paged-editor and positive-outcome gates remain off and the full development
environment goal remains active. Copy-header/parent relationship and new Source
receipt are observed by the pinned real fixture; raw headers and that new receipt
are not separately preserved. Saved status samples are not a complete continuous
event log, and process exit needs a separate live audit. The Pi recovery runs use the
production repository/navigation in a test activity; standalone viewer/export
tests use constructed immutable entries. Physical Galaxy, DeX,
wider locale/font/IME combinations, independent remote Linux and same-ID live
owner replacement remain separate acceptance requirements. No Wear emulator or
iPhone work is included.

### Export lifecycle follow-up

`export-lifecycle-green-02` passes four instrumentation cases in 27.560 seconds,
with cleanup passed on the dedicated emulator:

- Real DocumentsUI cancellation, followed by another launch and cancellation.
- Real Activity recreation while DocumentsUI is open, confirmed by a second
  activity content creation, then cancellation and a successful retry launch.
- Controlled saved-state restoration and late result delivery: new exports stay
  disabled until the original result is consumed; lost pending text is reported
  without a saved callback or destination creation.
- A controlled URI with a nonexistent provider reports failure, retains the
  original preview and permits retry without a saved callback. This is a provider
  opening failure, not a failure after bytes were written.

The late-result test was first observed failing on the enabled Export button in
`export-lifecycle-red-01`. The production correction retains the restored pending
marker until its callback arrives, preventing an old result from consuming a new
selection. If the picker never returns, that dialog instance remains fenced.
`export-lifecycle-green-01` passed the two controlled cases but failed the real
cases because the test relied on the Back key return value and ActivityScenario
required a resumed activity. The final tests observe the actual app return and
invoke recreation on the original stopped activity; those diagnostic failures
are not counted as product passes.

The final app APK is
`3af5fb3f669671d3f4c34e62a5fcbeafe08c29021ea36e6836a739f0307d03de`;
the test APK is
`505853d37011bcebee37d00ef79b686de202b04a8b145ad65c57ab9ad679e50b`.
The controller preserves source/APK snapshots and verifies installed package
hashes. No document is created by the real cancellation cases. The full local
quick-check passes in 97 seconds, with 3,651 app and 34 Wear tests and zero
failures, errors or skips. The earlier successful-save and Pi reopen evidence
above remains evidence for its recorded source/APK versions, not a rerun on this
follow-up APK.

### Writing operation ownership

The writing operation now belongs to an Activity-scoped
`EditorRecoveryExportViewModel`, rather than the dialog composition. It retains
only the current immutable entry while IO runs and uses the application resolver
for normal exports. Closing the dialog or rotating the Activity does not cancel
that operation. New exports stay disabled until the output stream has been
written and closed. The result remains available when the dialog is reopened;
the optional saved observer is notified at most once for that operation.

`SavedStateHandle` contains only an advisory writing boolean, never the text or
URI. Restoring that boolean yields an interrupted/possibly incomplete-file
warning and no success notification or automatic retry. This is not a durable
process-death journal: Android may retain a snapshot older than the write, so it
does not prove recovery from arbitrary process loss.

`export-io-red-03` reached an actual blocked injected stream, recreated the
Activity and failed because Export was enabled too early. The two earlier RED
attempts did not reach that boundary: the fixture initially blocked only bulk
writes and then waited without advancing the Compose scheduler. They are
preserved diagnostic runs, not lifecycle evidence.

`export-io-green-01` passes eight instrumentation cases in 52.901 seconds, with
cleanup passed: the previous four lifecycle cases plus injected write failure,
injected close failure, actual Activity recreation during blocked IO and dialog
close/reopen during blocked IO. Both blocked cases prove no premature saved
notification, a retained fence, exact bytes after release and one success
notification. Error cases retain the original preview, report failure and allow
retry without a success notification. These streams are injected, not failures
induced in a real document provider. Two unit cases separately verify idle and
restored interrupted states, marker-only saved keys and clearing the warning.

`export-io-real-saf-01` repeats successful actual-provider UTF-8/raw exports on
identical final APKs, passing in 20.702 seconds with cleanup passed. The actual
returned URIs contain the same independently expected 1,048,576 and 2,097,154
bytes and hashes recorded above; unique test documents were deleted. The saved
phone screenshot was inspected. Final app APK:
`97fc0d099b0ced0a7ba63f1f620877948204887559be83d3e8b7cb451d484950`;
final test APK:
`c4d8e56d562da7dc345d9934a52cce9217fe46b117e9239215e855626b0e60af`.
The full quick-check passes in 119 seconds: 3,653 app plus 34 Wear tests, no
failures, errors or skips. A scoped independent review found no blocking race or
Activity reference retention in this writing path. The default no-op saved
observer also consumes the one-time notification; a later observer does not get
a historical notification, while the visible result remains retained.

Still open: real provider write/close failures, real process death with a pending
picker or active write, physical Galaxy acceptance and the wider provider matrix.
The lifecycle/error plan gate therefore remains unchecked.
