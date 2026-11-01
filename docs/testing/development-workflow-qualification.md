# Actual native development workflow: local Android qualification, 2026-11-01

One Android emulator instrumentation run passed a single development workflow through production
`RemoteNavigation`, `RemoteScreen`, `DefaultRemoteRepository` and encrypted transport to one
Host-managed native Pi 1.4.0 TUI worker. The run took 82.84 seconds (one test). It used a private
TLS relay, disposable Git worktree and local bare remote, and a controlled PR/CI provider. The
test activity supplied pairing, lifecycle and validated-network signals and entered the captured
session via a notification; this does not qualify `MainActivity` or production networking.

The Android UI opened and edited `workflow.txt`, saved it, ran a shell command against the same
project, selected the file for review and explicitly confirmed commit, push and PR creation. The
shell returned exit code 0 and the expected marker; the fixture also saw the proof file. The UI
rendered the created PR and its running and passed check states, with screenshots captured for
both. The controlled provider supplied check input; the Host observed the PR and aggregate state.
The fixture label `fixture-build` is controlled input, not independently observed named-check
attribution.

The fixture recorded the same initial and current worker PID, session, runtime and project at
operation boundaries. Before each confirmation the test checked the expected local and remote
HEAD and mutation counters; final counters were one commit, one push attempt and one controlled
PR creation. Final local HEAD, remote feature HEAD and PR head agreed, and the PR had the expected
base, branch, title, body and non-draft status. The saved working file and committed Git blob were compared with the expected exact bytes, including
the final newline. An earlier fixture implementation normalized Git output; the raw-byte comparison
was corrected before this successful run.

The run used App base `6521b0008ee24cd803bb83a79615b8c0c827ad1b` and Host base
`ddb00a1c8bea27dfc1ecf6dd72dc880950de54a9`, both with the workflow test/fixture changes present
and uncommitted at execution. Installed APK SHA-256 values were
`9449710f0187b1a274470ba952ea1216d98f7eefee057c10b32463a75d1e5d67` (app) and
`19e4e9904feed85a52b5847b4c09893a967e724671e45436b3800ac918d1984e` (test). The runner recorded
fixture cleanup as passed. Local artifacts are under
`/private/tmp/pocketpi-paged-production.oge2ybh1/` with prefix
`native-development-navigation-fixed`: instrumentation log, final status, result and screenshots.
The source snapshots in `source-inputs.json` were captured after the run; they are useful for review
but are not an immutable record captured at execution time. These temporary artifacts are not a
durable CI record.

An earlier fixture startup failed due to fixture parsing; another attempt left the full editor in
the foreground while the test incorrectly treated the obscured composer as accessible. The test
was corrected to close the foreground pane explicitly before sending. Those failures do not by
themselves establish a product bug. The successful run is one emulator scenario: it does not
prove a model turn, ticket-to-session start, real GitHub or CI, physical phone/tablet, arbitrary
projects, interactive terminal behavior, or universal remote-development support.
