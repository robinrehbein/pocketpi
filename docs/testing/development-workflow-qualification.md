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

## Pinned tablet-viewport follow-up

The same chain passed again in 81.408 seconds on the owned API-36 emulator with a
1600×2560, 240-dpi override (approximately 1067 dp wide). This is a tablet-sized viewport,
not Galaxy Tab hardware. The pre-launch record reports clean App
`d3dbf7951808bc25af574df4f36f1f82fbcdfeec` and Host
`bdd730dd785aabe074c1bf7b1e52ee8cdcb3247b`, with the same app/test APK hashes as above.
The binaries were reused without source changes; this is a local build/run record, not an
independently attested CI build. Final status again records matching owner identities, exact
file/commit hashes, matching local/remote/PR HEAD and one commit, push and PR creation.

The saved screenshot shows the session list beside the chat, the passed PR chip and the shell
marker/exit-code panel. Root inspected it; the independent runtime-evidence review found no
blocking issue for either bounded run. Prefix: `native-development-tablet-pinned-v2` in the
same private lab. Display metadata `display-native-development-tablet-pinned-v2.json` records
setup and restoration to 1080×2400 at 420 dpi. The fixture and copied credentials were cleaned
up; the exclusively owned emulator and private ADB remain available for later work.

## Native agent turn in the joined workflow: phone, 2026-11-01

A subsequent phone-emulator run (`native-agent-first`) passed the joined workflow with a real
agent turn inserted after the file save and before the manual shell command. Android sent the
fixture prompt through the composer and displayed the assistant marker. The same Host-managed
native Pi 1.4.0 worker executed the public Pi agent loop: its ordered trace records the prompt,
three provider calls, completed `read` and `bash` tool executions, and `agent_end`. The second
and third provider calls received the respective tool results with matching digests; the bash
result had exit code zero. The trace retained the same worker PID and session, and the final
status preserved the same owner. The workflow then completed the manual shell, confirmed commit,
local push, PR creation and controlled pending-to-passed checks, with one commit, push and PR.
The final trace was checked again after those steps. Android instrumentation reported **OK (1
test)** in **82.97 seconds**; fixture cleanup also passed.

This used a deterministic offline provider in the actual public Pi agent loop, not an external
model. It establishes the bounded local tool and UI path, not external model quality, ticket
startup, real GitHub or CI, or physical-device qualification. The previous phone/tablet results
above did not include this agent turn; a tablet run of the extended flow was still in progress
when this phone result was recorded.

Evidence is in the private lab `/private/tmp/pocketpi-paged-production.oge2ybh1` under the
`native-agent-first` prefix: instrumentation log, final status, ordered agent trace, result and
screenshots. The result records app HEAD `bca918c217a82617a48330761cefbfe0303043be` and Host
HEAD `bdd730dd785aabe074c1bf7b1e52ee8cdcb3247b`, both with local fixture/test changes. It also
records pre-launch snapshots and SHA-256 digests of the Android test and three Host fixture
sources. The installed app and test APK digests match their build artifacts:
`9449710f0187b1a274470ba952ea1216d98f7eefee057c10b32463a75d1e5d67` and
`becf5ae7f1c3e439303033c66c5f8907fa625ce65cf5bda6b9de365899d6015c`, respectively.

### Tablet follow-up with the native agent loop

The same extended flow passed on the 1600×2560, 240-dpi emulator viewport in **83.499 seconds**
(`native-agent-tablet`). Pre-launch source snapshots and both installed APK hashes match the
phone inputs. Final status retains exactly eight agent events and matching identity/context
proofs after one confirmed commit, local push and controlled PR creation, followed by passed
checks. Screenshots show the separate user prompt, read/bash cards, assistant reply, shell
output and PR chip in the wide layout. The independent review found no blocking issue in
either recorded run. This remains a deterministic local provider test on an emulator.

The runner reports fixture cleanup passed. `display-native-agent-tablet.json` records restoration
to 1080×2400 at 420 dpi. The owned emulator and private ADB remain available; no personal Host
or external model/PR service was changed. The extended tablet run supersedes the earlier
"in progress" checkpoint without broadening its qualification limits.
