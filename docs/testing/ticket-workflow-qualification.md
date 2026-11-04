# Ticket-started native workflow: local Android qualification, 2026-11-01

**Phone emulator: passed on the earlier app snapshot. Tablet emulator: passed after the wide-layout fix.** The phone run
`ticket-native-phone-branch-fixed` completed one instrumentation test in 114.246 seconds.
It used production Android navigation and encrypted transport, production Host ticket start,
a real local Git worktree and a Host-managed native Pi worker. The ticket backend was a
controlled in-memory Plane API; the provider, PR and CI were local fixtures.

The UI opened the ticket list and detail, supplied the note and explicitly confirmed start.
The successful start returned the worker session and the app automatically navigated to it.
Final status recorded exactly one start and worker, with the same session, native owner and
actual worktree on branch `task/dev-1-ticket-workflow`. The worker received the automatic
ticket prompt before the UI file edit. Its distinct assistant marker and prompt digest were
checked; the complete 11-event trace bound both turns to the same PID and session and recorded
model calls 1–4 and the later successful read and bash tool results.

The same UI workflow saved the file, ran the later agent turn and a manual shell command, then
explicitly confirmed one commit, one push to the owned local remote and one controlled PR.
Controlled CI progressed to passed. Owner and Git mutation assertions ran throughout. The
passing captured test source checks zero start requests and workers, null job/owner fields and
unchanged Git state immediately before confirmation. Those assertions passed as part of the
test; no separate pre-confirmation status snapshot was retained.

The earlier `ticket-native-phone-first` run failed after 111.417 seconds at a stale test
assertion expecting the old `fixture-feature` PR branch, after the ticket start and subsequent
workflow had reached PR creation. The assertion was corrected to derive the expected PR branch
from the verified actual worker branch. The passing run used the corrected source; the earlier
failure is retained as test history and does not establish a product defect.

Evidence is retained under `/private/tmp/pocketpi-paged-production.oge2ybh1/` with the
`ticket-native-phone-branch-fixed` prefix: instrumentation and fixture logs, result, final status,
agent trace, screenshots, and pre-launch source and APK snapshots. Independent review
(`/private/tmp/ticket-native-review.md`) checked the five captured source snapshots and both APK hashes,
the passing result and final evidence. The runner recorded fixture cleanup as passed. The initial
failure artifacts use the `ticket-native-phone-first` prefix.

## Wide-layout defect and tablet follow-up

The first tablet run timed out after 64.646 seconds waiting for `ticketsList` after the ticket
menu action. Diagnostic capture `ticket-native-tablet-scene-red` showed the project placeholder
and `chatPane`, no ticket list, and one loaded ticket project. The ticket start and worker counts
remained zero. The wide scene mapped the ticket page to a detail column above Projects but
rendered the empty-project placeholder whenever the Sessions column was absent, even if a detail
page existed. The same ordering affected other auxiliary detail pages above Projects. The fix
renders a present detail first, retaining the project placeholder when both detail and list are
absent and the chat placeholder when only detail is absent.

The first focused unit attempt failed earlier because its fake host was absent from the host list;
a corrected setup reached the ticket menu and reproduced the persistent placeholder. That
meaningful RED is `/private/tmp/pocketpi-ticket-wide-red2.log`. The focused regression passed
after the fix, along with APK assembly (`/private/tmp/pocketpi-ticket-wide-green-build.log`).
The app and Wear quick-check subsequently passed in 93 seconds: 3,468 app and 34 Wear tests,
with no failures, errors or skips (`/private/tmp/pocketpi-ticket-wide-quick-check.log`).

The corrected tablet run `ticket-native-tablet-scene-fixed` passed one instrumentation test in
127.041 seconds. Its result recorded fixture cleanup passed, and the owned display was restored.
The pre-launch manifest records seven source snapshots and two installed APK snapshots; this run
used the corrected production app APK, unlike the earlier passing phone run. Instrumentation,
status, trace, screenshots, inputs and result are retained under that prefix in the same private
lab. Independent review found no blocking issue in the tablet artifacts. The separate
non-ticket regression subsequently passed as recorded below.

This is a bounded local emulator result. It does not qualify external Plane, GitHub, CI or model
behavior, real external writes, a physical handset, or the complete remote-development goal.

## Final regression checkpoint

The non-ticket regression reported OK (1 test) in 88.476 seconds and cleanup passed. Its final
status retains exactly eight agent events, one commit, one local push and one controlled PR,
with passed checks. The captured source and installed APK inputs are recorded under the same
private lab prefix `native-agent-legacy-after-ticket`. This preserves the prior entry path
after the optional ticket provider turn and wide-layout correction.
