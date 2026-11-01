<!-- Current recovery qualification: testing/mainactivity-process-recovery-qualification.md and testing/fresh-client-handoff-qualification.md. Historical checkpoints retain their original bounds. -->
# Goal: PocketPi as a complete remote development environment

## Public iOS Simulator feasibility checkpoint on 2026-11-01

A bounded public-XCTest command bridge passed on one owned iPhone 17 Pro / iOS 26.5 simulator: screenshot capture, coordinate tap, exact text entry, Home/background, Activate preserving state, three drags to the final item, invalid-coordinate rejection, two idle replies and a later successful input. Root visually inspected actual XCTest and same-UDID simctl captures; the portrait scale is 3:1 in both axes. Build/test and cleanup passed; the owned simulator was deleted, original device IDs restored and the xcodebuild process ended.

This is a feasibility prototype, **not an integrated PocketPi iOS backend**. See [qualification](testing/ios-simulator-probe-qualification.md) for exact evidence and the two preserved failures, and [backend design](plans/2026-11-01-owned-ios-simulator-backend.md) for ownership, negotiated platform profiles and remaining runtime/Android integration. Existing Android capabilities and the paged editor's disabled production gate are unchanged. The overall goal remains active.

## Native custom terminal checkpoint on 2026-11-01

The actual MainActivity / native Pi 1.4.0 custom-component path passed on phone and tablet emulator layouts. The test confirms takeover from an owned local fixture attachment, verifies its revocation and stale-input rejection, visually checks the actual rendered component, selects through terminal UI keys and proves the exact source result plus a later command in the same worker/session. Both cases and cleanup passed; 43 source pins, installed/build APKs and the six-event source trace were independently audited. Existing text process recovery also passed.

See [custom terminal qualification](testing/native-custom-terminal-qualification.md) for exact labels, historical failures and scope. This is one deterministic public custom component, not arbitrary component or physical-device qualification. The separate [iOS public API feasibility](plans/2026-11-01-ios-simulator-feasibility.md) identifies a missing Mac backend: capture and public XCTest input primitives still require a working ownership/transport prototype. The paged editor's public capability remains off pending its remaining authority/lifecycle gates. The overall goal remains active.

## Real Android image delivery checkpoint on 2026-11-01

Four scoped phone/tablet emulator cases passed with cleanup: unchanged attachment clearing and offline replacement retention after an exact recovered image-prompt receipt. The actual system picker, importer, encrypted stores, attachment upload and native Pi SDK model path are exercised. Source JPEG integrity and SDK-normalized PNG integrity are checked separately. The observer records exactly one matching Android write attempt, and the host records one prompt request despite the withheld acknowledgement and echo.

See [image qualification](testing/native-android-image-delivery-qualification.md) for exact labels, historical failures and limits. The held image turn remains running at the checkpoint. No physical Samsung device qualification is implied. Earlier intermittent picker/menu failures remain recorded without a proven common cause; only test readiness and diagnostics changed. The overall goal remains active.

## Actual process recovery and fresh Android pairing checkpoint on 2026-11-01

Four phone/tablet-layout cases now recover the real Application-created repository and encrypted
stores after force-stop and a normal launcher. The same native worker returns one exact source
receipt with no second prompt attempt; untouched composer text clears and edit-and-revert text
survives. The user reopens the chat through normal UI after reconnect; automatic last-chat and OS
saved-task restoration are separate. See [process qualification](testing/mainactivity-process-recovery-qualification.md).

Fresh phone/tablet-layout cases reset only the verified owned emulator app, pair a distinct client
through the real Paste Code interface and continue the same native session/assistant message with
one Stop and one follow-up. See [fresh-client qualification](testing/fresh-client-handoff-qualification.md).
All six cases have independent source/APK/runner/proof review. Physical devices, independent Linux,
production relay/provider, prolonged background/Doze behavior and remaining paged-editor gates
retain their separate qualification. The overall goal remains active.

## Actual MainActivity and live-run checkpoint on 2026-11-01

The actual Application-created repository, MainActivity and Android foreground service now pass
phone/tablet-layout emulator acceptance. A real native delta arrives while the Activity and
process remain backgrounded and the service keeps the connection. Returning preserves the
same Activity, session, worker and assistant message, with one marker and no repeated prompt.
The opt-in test uses a scoped fixture CA through a debug factory; no repository or service is
replaced. See [production-entry qualification](testing/production-entry-live-run-qualification.md).
The final App checks pass 3,538 tests; the unchanged 34-test Wear result is up to date.
Physical Samsung, long background/Doze behavior, production relay/provider, process death through
MainActivity and fresh-device handoff remain separate qualification. The overall goal is active.

## Held-event and current Linux qualification checkpoint on 2026-11-01

Upstream TOOLING-1110 is integrated while preserving causal epoch fencing and exact question-close
receipts. The required checks pass 3,538 App and 34 Wear tests. A new-process recovery and full
phone/tablet native workflows pass on fresh APKs, with independent source/APK/evidence review.
See [held-event qualification](testing/held-event-integration-qualification.md).

The current packaged Linux image passes frozen installation, consumer and service-lifecycle
checks, plus an isolated connected native-worker/reconnect fixture. The public Dev Containers
adapter also passes with an explicitly fixture-derived image and its actual supervisor; see
[Linux qualification](testing/linux-host-qualification.md). Native and RPC duplicate editor
commits are qualified privately; the remaining editor gates keep public capabilities disabled.
The actual MainActivity/background path was the next acceptance item at this checkpoint and is
qualified separately above. Physical Samsung, independent-host and external-provider/CI evidence
remain open. The overall goal is active.

## Session epoch and replay integration checkpoint on 2026-11-01

The App now integrates upstream list epochs and compaction deduplication while protecting the
selected timeline, titles and editor-close receipts. Only an authoritative read confirms an epoch
replacement; a previously unseen late event cannot permanently retire the actual current epoch.
Rejected pages cannot settle questions/receipts/tasks or release an offline queued prompt.
Exact question instances are tracked when closes apply, including replay and failed waits.
Cross-project title candidates preserve cache identity without inventing a missing title.

The final 3,527 App + 34 Wear tests pass. Four actual phone/tablet process-recovery cases and both
complete native development workflows pass on the same final APKs and runtime sources, with
independent artifact/hash review and cleanup. Root inspected the tablet's adaptive workflow result.
See [epoch/replay qualification](testing/session-epoch-replay-qualification.md) for evidence and limits.
The runtime proofs use the local Mac Host, emulator, fixture provider and controlled local Git/PR/CI;
physical Samsung, independent Linux, external provider/CI and production MainActivity/service
restoration remain separate qualification. The overall goal is active.

## Android process recovery checkpoint on 2026-11-01

Phone and tablet emulator runs now prove recovery of an accepted prompt after the Android process
is force-stopped with neither result nor user echo received. A new process loads the same encrypted
journal, receives the exact source receipt and sends no second prompt; the native Pi worker stays
unchanged. Untouched restored text clears, while an edit away and back to the same text survives.
Durable per-field provenance also protects intentional empty text and mixed quote/attachment edits
in repository regressions. See [process qualification](testing/prompt-process-recovery-qualification.md).
The Host upstream was integrated on a separate preserved branch; see [integration checks](testing/host-upstream-2026-11-01.md).
This qualifies production repository/transport/storage inside a test Activity. Production MainActivity
startup/background-service restoration, Host/Pi process death, fresh-device handoff, physical Samsung
and an independent Linux machine remain open. Historical checkpoints below retain their original bounds.

## Accepted initial prompt lost-ACK checkpoint on 2026-11-01

Phone and tablet workflows passed actual initial prompt acceptance with acknowledgment and user
echo withheld from Android, visible NOT_CONFIRMED state, and reconnect resolution from the exact
source-user receipt. Android independently counted one matching write attempt through workflow
completion; the same native worker executed the prompt once and continued through Stop, follow-up
and the full controlled development chain. The feature uses separate source/Host/client negotiation
and preserves edits to the restored composer. A real Pi SDK image-resize hint regression was fixed
and tested separately; it is not Android image-upload E2E qualification. See
[lost-ACK qualification](testing/native-prompt-lost-ack-qualification.md).
Process death, fresh-device handoff, physical Samsung and independent Linux remain unqualified.

## Active native turn reconnect checkpoint on 2026-11-01

Phone and tablet real-native-Pi workflows now pass an actual socket cut while a turn remains
active, reconnect to the same worker with exactly one visible partial of the same identity, then
Stop and continue development. A reproduced bug dropped the held prefix in full snapshots;
the Host now retains only the currently observed native assistant stream with source/epoch/turn
validation and terminal precedence. Recovery evidence includes a full snapshot containing the
held streaming prefix, not merely an empty replay. See [active-turn qualification](testing/active-turn-reconnect-qualification.md).
Lost acknowledgments, process death, physical Samsung and independent Linux remain unqualified.

## Interrupted assistant outcome checkpoint on 2026-11-01

Interrupted partial replies now carry a negotiated sanitized outcome and display a neutral
Interrupted / Unterbrochen label, retain Copy, and do not count as Errors. Genuine and legacy
failures remain errors. Phone and tablet real-native-Pi workflows passed the label and zero-error
checks before and after idle reconnect, then follow-up and the full local development chain.
The actual old-product regression attributed the earlier Errors(1) chip to the interrupted reply.
Mixed-client, old-Host bridge, snapshot/replay and a blocked-outbox negotiation race are covered
by focused regressions. See [interruption qualification](testing/interrupted-assistant-qualification.md).
Physical Samsung, independent Linux, active-turn disconnection and process death remain unqualified.

## Native Stop and idle reconnect checkpoint on 2026-11-01

Phone and tablet emulator workflows passed a visibly streaming native Pi turn, real Android Stop,
actual provider cancellation and idle socket disconnect/reconnect to the same worker, followed by
one successful new turn and the file/shell/local Git/controlled PR/CI chain. Exact traces and Stop
counts were reasserted at workflow completion. This does not qualify a disconnect during active
work, a lost acknowledgment or process death. See [lifecycle qualification](testing/native-lifecycle-qualification.md).
The tablet screenshot exposed an error-count classification question after deliberate Stop;
this historical observation is resolved by the interrupted-outcome checkpoint above.

## Native session export checkpoint on 2026-11-01

Host-owned native workers now support capability-gated PocketPi HTML snapshot export using public
Pi session APIs. Phone and tablet emulator workflows passed complete-tree and active-leaf checks
through the actual export/share URI, including a large inactive branch and multi-chunk transfer.
The snapshot preserves public entries and raw JSON; it does not reproduce Pi custom renderers.
See [native export qualification](testing/native-export-qualification.md) for exact inputs, review
regressions and limits. Independent Linux and physical Samsung qualification remain open; neither
a device nor an authorized SSH test host is currently available.

## Ticket-started native Android workflow checkpoint on 2026-11-01

Phone- and tablet-sized Android emulator runs passed ticket selection and start through production
navigation, followed by a real native Pi worker's automatic ticket turn and the file, agent, shell,
confirmed Git, controlled PR and CI workflow. The tablet run passed after fixing a wide-layout
placeholder that concealed detail pages above Projects. The phone passed on the earlier app
snapshot; the tablet used the corrected app. The fixture uses an in-memory Plane API and
deterministic local provider. External services, real model behavior and physical devices remain
unqualified. See [ticket workflow qualification](testing/ticket-workflow-qualification.md).

## Native agent turn in the Android workflow checkpoint on 2026-11-01

Phone and tablet-sized emulator runs completed the joined file edit, real native Pi agent/tool turn, manual shell,
confirmed commit, local push, PR and controlled CI workflow. The agent used a deterministic
offline provider; its ordered tool results and the unchanged worker owner were verified through
the end of the workflow. This does not qualify external model quality, ticket startup, real
GitHub/CI or physical devices. Both runs passed independent evidence review. See
[development workflow qualification](testing/development-workflow-qualification.md).

## Actual native Android development workflow checkpoint on 2026-11-01

Phone and tablet-sized Android emulator runs completed one production-navigation workflow with a Host-managed
native Pi worker: file edit and save, shell execution, explicitly confirmed commit and local push,
controlled PR creation, and rendered running and passed PR checks. This is a bounded local
fixture result, not ticket-to-session, real model/GitHub/CI or physical-device qualification.
The saved file and committed blob were checked as exact bytes. See
[development workflow qualification](testing/development-workflow-qualification.md).

## Current derived Linux package and Dev Containers checkpoint on 2026-11-01

The freshly packed current Host archive passes the Linux consumer and native PTY verifier in an
explicitly derived local image. The unchanged Dev Containers adapter also passes disconnected
setup, stop/restart and same-container reuse against that image. The owned container and state
volume were cleaned up. This does not establish a versioned Dockerfile build, a connected
current-source Devcontainer chain, complete CLI-distribution provenance or an independent
Linux host. Older connected-image checkpoints below describe their historical artifacts. See
[Linux qualification](testing/linux-host-qualification.md).

## Confirmed pull-request checkpoint on 2026-10-31

The local Host and Android worktrees now implement explicit PR preparation, confirmation and
creation, with a separately authorized read-only recovery observation. Android persists the
edit draft and the confirmed intent independently; an unknown result is never automatically
created or pushed again. Encrypted Android pane/transport tests passed on phone and tablet
emulators against a controlled provider, including dropped-response recovery after reconstructing
the repository and reading the same encrypted draft file. The Pi peer and network signal were
synthetic, and the pane was hosted by a test harness. This does not establish a full RemoteScreen,
real Pi worker, OS process death or real GitHub PR creation. Physical Samsung devices and an
independent Linux host were unavailable; Wear and broad suites are pending. See
[PR qualification](testing/pull-request-qualification.md).


## Packaged protected-preview checkpoint

The same locked Linux image now passes a combined encrypted synthetic-device browser
and protected-preview flow: separate grants, real HTTP/WebSocket, ACK-paced JPEG,
text-triggered POST, foreign-port denial with a reachable control, and owner revocation.
No production runtime changed. Active-stream reconnect, Android UI against the packaged
host, independent Linux and physical Samsung qualification remain open. See
[Linux qualification](testing/linux-host-qualification.md).


## Owner browser configuration and encrypted Linux-image checkpoint

The owner CLI now configures the Chromium path explicitly and manages browser switches
and grants. The refreshed locked Linux image passes real CLI setup, encrypted synthetic
device browser access, ACK-paced JPEG, input and owner grant revocation, plus its separate
renderer sandbox proof. This qualifies the bounded local image flow; protected preview,
active browser reconnect/handoff, physical devices and independent Linux remain open.
See [Linux qualification](testing/linux-host-qualification.md).


## Current source and frozen browser qualification checkpoint

The merged Host navigation work passes 6,604 extension tests with 37 explicit skips,
plus extension format/type checks and 76 deployment/profile helper tests. The maintained
browser qualification runner rejects mismatched engine/kernel/image/profile/fixture
before launch and passes against its explicitly frozen qualified image. This does not
promote that historical image to the newer source version or complete service-level
browser/preview deployment. See [Linux qualification](testing/linux-host-qualification.md).


## Packaged CLI and Dev Containers checkpoint on 2026-10-31

The repository now contains a guarded Dev Containers adapter with explicit owner setup,
private persistent state, nonroot/read-only operation and verified restart/container replacement.
The corrected standalone image also passes the regular Host CLI connected flow: private TLS
relay, authenticated encrypted pairing, native Pi worker/PTY, public snapshot, deterministic
provider turn, remote shell and clean shutdown. A qualification-only image now also passes that connected flow through actual Dev Containers
CLI up and its supervisor, including a deliberate relay cut, preserved worker and clean
container restart with unchanged configuration. This remains local Docker Desktop evidence.
Independent remote Linux, Chromium packaging, lost-ACK/process-recovery and physical Samsung
qualification remain open. See [Linux qualification](testing/linux-host-qualification.md) for exact boundaries.


## Persistent Linux core checkpoint on 2026-10-31

The standalone package now builds into a nonroot core host image with explicit persistent private
state. Actual local read-only/runtime tests pass setup, duplicate identity protection, restart,
provisioning-variable removal, SIGTERM/SIGINT and owned cleanup. This is an offline core-service
qualification; connected Android/relay use, Chromium, the Dev Containers adapter and an independent
remote Linux host remain open. See [Linux qualification](testing/linux-host-qualification.md).


## Standalone Linux package checkpoint on 2026-10-31

The current Atlas distribution now passes a separate Linux installation and offline/read-only
verification of public Pi CLI, installed Host CLI and the actual production PTY wrapper, without
checkout dependency symlinks or Mac-native modules. Version/integrity locks and safe context
preparation are maintained in the repository. This closes the clean-package-installation gap;
it does not qualify the OCI runtime, Chromium sandbox, Dev Containers adapter or an independent
remote Linux host. See [Linux qualification](testing/linux-host-qualification.md).


## Integrated Android emulator checkpoint on 2026-10-31

PocketPi and the public Pi `remote_emulator` tool now share an explicitly configured host-owned
AVD through encrypted transport, owner grants, control leases and bounded PNG frame delivery.
Real phone/tablet emulator smoke flows pass native Home, image delivery, Pi snapshot, bidirectional
control handoff, device-grant revocation and owned-process cleanup. The final App/Wear suites pass
3,081/34 tests. Host checks pass; its full suite has one screenshot/resize failure that passes in
isolation (6,399 passed, 27 skipped). Full-host green is not established. Details and qualification
boundaries are in [emulator integration](testing/emulator-integration.md). The preceding backend
checkpoint below describes the earlier component-only state. A controlled lost-input-response
reconnect also passes on phone/tablet emulators: old ownership is revoked, input is not repeated,
and the new connection resumes as a reader. See [reconnect qualification](testing/emulator-reconnect.md).
The broader lifecycle/long-session, physical Samsung and independent Linux/devcontainer gates remain open.


## Owned Android emulator backend checkpoint on 2026-10-31

A host-owned emulator/private-ADB runtime now implements authenticated capture and finite
key/tap/swipe input with identity, geometry, queue and cleanup guards. Two real Mac SDK tests
prove stable rendered Settings/Launcher/search images, HOME and a search-field tap, revocation
cleanup and refusal of a foreign ADB listener. Independent review corrections and 55 focused
tests pass; the integrated full extension suite passes 6,357 tests with 27 skipped. This is an
implemented backend, not yet an emulator capability in PocketPi. Owner grants, Pi/device service,
wire protocol and app pane remain the next package. Exact scope and evidence are in
[owned emulator runtime qualification](testing/owned-emulator-runtime.md).


## Android emulator feasibility checkpoint on 2026-10-31

An authenticated local gRPC capture plus a fixed ADB HOME command produced 30 frames with 28
different images, visually moving from Settings to Launcher. Unary scaled capture is fast in a
small static Node 24 probe; the gRPC key input path failed a controlled semantic test and is not
qualified. There is no emulator product capability yet. Exact limits, authentication checks and
the next implementation gates are in [emulator feasibility](testing/android-emulator-feasibility.md).


## Transactional local controls checkpoint on 2026-10-31

All eight local control groups now persist independently from concurrent unrelated saves. Failed
browser/terminal/preview grants or write-switch/root changes cannot leak into a later device save;
successful changes survive stale snapshots. The effective browse root changes only after successful
persistence. Actual encrypted browser-grant and folder-listing regressions reproduce the original
defects and pass after correction. The host/client/protocol suite passes 2,878 tests with 17 skipped;
package checks and independent review pass. See the
[implementation/qualification plan](superpowers/plans/2026-10-31-control-transactions.md).


## User-facing protected preview checkpoint on 2026-10-31

The host policy/runtime is now connected to session authorization, owner CLI commands and a
separate Android Preview pane. Final phone/tablet emulator flows pass with actual encrypted
transport, form input, WebSocket updates, read-only reconnect and active grant revocation.
Real Vite self-accepted HMR passes separately on Mac and Linux. The complete extension suite
passes 6,275 tests (25 skipped); the required app check passes 3,066 app and 34 Wear tests.
Three host and two app review rounds corrected lifecycle/persistence races and found no further
actionable finding in the final patch. Exact scope, commands, evidence and remaining gates are in
[preview integration qualification](testing/protected-preview-integration.md). Earlier checkpoints
below describe their historical component state; daemon/app wiring is now implemented. Physical
Galaxy devices, independent remote Linux/devcontainer, simulator streaming and whole-workflow
qualification remain open. Equivalent transactional protection for older control fields is now implemented as described above.


## Functional host preview runtime checkpoint on 2026-10-31

An endpoint-scoped host renderer now runs ordinary HTTP, POST and WebSocket preview traffic with
separate cookies/profiles, bounded input and captured frames. Real Mac/Linux tests pass (47/49),
including a foreign-server denial and controlled WebRTC UDP check with a working positive control.
A Linux-only runtime difference was reproduced and fixed using both Chromium variant switches.
The component preserves Chromium sandboxing and does not relax artifact/terminal WebViews.
Daemon session/grant wiring, owner commands, app pane, encrypted Android acceptance and actual
framework HMR remain open; preview support is not yet advertised. Exact evidence and limits are
in [protected preview runtime](testing/protected-preview-runtime.md).

## Protected preview and bounded Linux sandbox checkpoint on 2026-10-31

A separately authorized exact-port preview policy now passes nine tests, including atomic saved
state and concurrent mutations. It is not yet connected to a preview service or advertised as a
capability. A real standalone Android probe loaded a static WebView in its normal service but
failed construction in the isolated no-INTERNET service on the tested API 36 runtime. The renderer
boundary must be resolved before enabling the preview. See [preview foundation](testing/protected-preview-foundation.md).

An independent local Linux run passes all 26 browser/worker tests using a bounded experimental
Moby-derived seccomp profile, all capabilities dropped and no-new-privileges, replacing the prior
unconfined diagnostic profile. Its three added rules still require intended-host/security review;
it is not a production devcontainer qualification. See [Linux qualification](testing/linux-host-qualification.md).

## Last-participant browser retention checkpoint on 2026-10-31

The browser now survives the last recovery-enabled device disconnect for its authorized lifetime,
with immediate ownership revocation, pending input cleanup and read-only reconnect. Actual Pi joins
only after the zero-participant gap and verifies the original browser and non-first login tab.
Phone/tablet emulator flows pass (23.315 s / 10.325 s), including uncertain input, a newer unsent draft,
no automatic input/control replay, explicit handoff and same-tab login-cookie verification.
Independent review found two lifecycle races; regression tests and corrections pass, and the final
review found no further blocker. The host suite passed 3,023 tests with 23 skipped. A sporadic
pre-dispatch input loss observed in an earlier Android run remains an open qualification item.
See [retention qualification](testing/browser-context-retention.md) for exact scope and evidence.

A local Linux image now runs 26 actual browser/worker tests with native Linux dependencies.
Short private Chromium temporary paths, hidden headless scrollbars and stable managed-tab order
fix concrete Linux/runtime defects. Docker's outer seccomp profile was diagnostically relaxed;
secure reproducible packaging, an independent remote Linux host, physical Samsung devices,
protected previews and emulator streaming remain open. The complete goal is not achieved yet.

## Reconnect and native Linux worker checkpoint on 2026-10-31

A real interrupted-input flow now passes on phone and tablet emulators (14.175 s / 11.215 s).
The fixture applies one input, withholds its reply, and cuts the actual Android TLS/WebSocket
connection. Actual Pi proves old ownership removal before allowing reconnect. The app restores
the same selected tab as a reader, preserves a newer unsent draft and shows the uncertain
outcome; neither input nor acquisition is repeated automatically. Explicit control and the
final same-tab login-cookie check pass. The required check passed 3,058 app and 34 Wear tests.
The standalone activity supplies availability signals; physical network/lifecycle and
last-participant/process-death recovery remain open. See [reconnect evidence](testing/browser-reconnect.md).

An isolated Linux/arm64 native dependency build also succeeded without downloads. Agent and
independent root runs started an actual HostDaemon-owned Pi 1.4.0 PTY worker, matched actual
Pi/PTY/registered owner PIDs, completed an offline test turn and ran a shell command over the
encrypted loopback device path. This used Node 24.22.0; Mac tests use 24.18.0. This is a native
worker subset, not packaged Linux/devcontainer or Android/browser qualification. Exact scope
and remaining gates are in [Linux qualification](testing/linux-host-qualification.md).

## Shared Pi and Android browser checkpoint on 2026-10-31

The local combined fixture now connects the actual Pi 1.4.0 CLI and public remote extension to
the Android encrypted transport and native browser pane. A phone run passed explicit Pi-to-device
handoff, exact local form login, Android-to-Pi handoff and a protected request from the original
Pi browser tab carrying the login cookie. The provider is a deterministic offline fixture; this
is evidence of harness integration, not a neural model choosing actions or a real website login.
Final-source phone/tablet flows passed in 8.315 s and 10.898 s; the app quick-check passed
3,053 app and 34 Wear unit tests. Exact scope and evidence are in
[shared browser qualification](testing/shared-browser-handoff.md).

The run exposed an initial-viewport ordering defect: a watch at revision zero could release
newly acquired control before the first resize. The client now waits for viewport configuration.
Manual reader Refresh obtains authoritative Open metadata, so a viewport configured later by
Pi can be displayed without acquisition. A held lease still requires its sequence-bearing
Document event or its own viewport acknowledgment before adopting a newer revision. A locally
refused text send now shows a translated notice and preserves the draft, with no automatic retry.

[Linux qualification](testing/linux-host-qualification.md) confirms isolated Linux execution and
Pi CLI startup in existing local containers. Native PTY binaries and browser tooling are absent
from the inspected environments; complete independent Linux/devcontainer qualification is open.

## Browser document recovery checkpoint on 2026-10-31

The negotiated `session.browser.recovery.v1` increment keeps the known control lease across
main-document changes in the configured viewing tab. It fences stale positional input, drains
admitted operations, resets held input and gives the authenticated owner the host's next input
sequence. Android replaces old frame subscriptions, preserves unsent drafts and never replays
uncertain input or automatically acquires control. Background-tab changes do not invalidate the
current viewing tab. Commands without a document revision do not fence every stale semantic intent.

The Android quick-check passed 3,044 app and 34 Wear unit tests; the final host Remote suite
passed 3,018 tests with 23 skipped. The encrypted Android fixture proves page-initiated navigation,
same-lease recovery without another acquire, preserved draft, blue JPEG and Compose output, and
480 CSS pixels of real wheel scrolling on phone/tablet emulators. At that checkpoint, a send during IME-driven viewport changes could be refused silently while
preserving its draft. The later increment above adds explicit feedback for local refusal;
physical-device transition qualification remains open. Exact runs and limitations are in the
[transport evidence](testing/browser-transport-e2e.md).

A separate isolated probe now starts the actual Pi 1.4.0 CLI with its public extension API and a
local offline faux provider. Three real `remote_browser` calls open, acquire and create a tab;
actual Chromium visits the local fixture, and cleanup succeeds. This proves harness integration
without a paid model request. It does not combine the Android flow with a real Pi turn or qualify
a neural provider. Shared login/handoff/reconnect, protected HTTP/WS previews, emulator streams,
independent Linux/devcontainer and physical Samsung qualification remain open. The overall goal
is active; no release, deployment or live host activation has occurred.

## Real browser transport checkpoint on 2026-10-31

After integrating current upstream branches, a real Android instrumentation flow now exercises
TLS/WSS, authenticated encrypted transport, the repository, HostDaemon, actual owned Chromium
and the native browser pane. It exposed and fixed a missing browser capability whitelist entry;
the new repository regression failed before that fix and passed afterward. The full Android
quick-check passed 3,028 app and 34 Wear tests; the final host Remote suite passed 3,004 with
23 skipped. The stronger phone flow passed, and a tablet diagnostic flow also passed, including
independent pointer event counters, a unique form value and the visible green submit frame.

Pre-fix phone/tablet runs exposed concurrent screenshot/resize restoring stale viewport metrics.
Per-tab CDP transactions now serialize that boundary, retain ownership through actual completion
after abort and recheck authority before queued dispatch. Both final phone/tablet workflows and
60 targeted host regressions passed. These results do not establish universal stability. The
session IPC peer remains synthetic, and physical Samsung, real Pi model turns, independent
Linux/devcontainer, protected preview and emulator qualification remain open. See
[the transport evidence and reproduction guide](testing/browser-transport-e2e.md).

## Earlier browser viewer checkpoint on 2026-10-31

The local working trees now contain a native shared-browser pane, bounded JPEG decoding,
explicit control acquisition/handoff, tab and navigation controls, touch/text/keyboard input,
and encrypted frame subscriptions. The host captures its owned Chromium tab and rechecks
session, grant, lease, viewport and peer identity across capture and delivery. Client tests
cover delayed replies, uncertain input, hidden panes, session changes and bounded frame state.

The focused Android tests and debug/test APK assembly passed. The native pane component test
passed on an API 36 emulator at phone dimensions and at 2560x1600, density 320; both renderings
were inspected. The test displays an actual Chromium capture but simulates control responses.
This is not an end-to-end Android-to-host result or physical Galaxy qualification.

The host increment is committed locally as `2440314b3`. Its full Remote suite passed 2,952
tests with 23 skipped; extension checking and root formatting passed, and the frozen diff was
independently reviewed. Menu order and real Chromium shortcut failures were corrected.
Android review additionally found held-input/focus and late-acquire races; 40 focused browser
tests now pass after those fixes. The broader Android gate passed 3,019 app and 34 Wear unit tests and all compiled source
sets; debug and instrumentation APK assembly passed. The acquire reservation gap across hide/show and close/reopen was subsequently corrected
and independently re-reviewed. The final required quick-check passed in 90 seconds; both
APKs were rebuilt successfully for that reviewed source. No Android-to-host end-to-end, Linux/devcontainer, physical device or live activation claim is made.

## Verified local progress on 2026-10-30

The native terminal increment is implemented in local feature branches: PocketPi `284b5be`
and NOAH `5ab7e475d`. It provides opt-in, device/project-authorized native-worker terminal
control, ordered encrypted output, explicit takeover and an offline Android terminal pane.
An encrypted relay regression, a real macOS PTY smoke test and an API 36 WebView rendering
regression passed. The app gate passed 2,876 unit tests, debug assembly and lint; the host
Remote suite passed 2,825 tests with 23 skipped. These results precede later branch updates
and are increment evidence, not qualification of the entire goal.

A later local increment adds project-wide, case-sensitive literal code search through a separately
negotiated host capability. The host applies the file browser's existing access checks and bounds
its scan and response. Android shows matching paths and locations, opens current files through
the existing reader/editor, and protects unsaved edits. Host commit `7dd096981` passed the full
Remote suite with 2,836 tests and 23 skipped, extension checks and root formatting. The Android
increment passed `scripts/quick-check.sh`; physical Samsung and Linux qualification remains open.

Shared browser control, protected previews, emulator streaming and qualification on Linux,
devcontainers and both physical Samsung devices remain open. No release, deployment or live
host activation has been performed.

## Expanded goal agreed on 2026-10-30

PocketPi is Robin's complete mobile agentic development environment on Galaxy Tab S11 and
Galaxy S25. From either device, Robin controls pi and can intervene directly in code, terminals,
Git, pull requests, CI, browsers and emulators without anyone operating the development computer.
Mac and independent remote Linux hosts, including Linux devcontainers, are equal priority.

The qualifying workflow includes an interactive Android emulator and an iOS simulator on a Mac.
The Wear companion includes any feature that provides meaningful value on a watch, following
the owner clarification on 2026-10-31. Messages and questions are examples, not a feature ceiling.
For each new feature, assess its watch-specific usefulness, readable context, interaction cost,
accidental-action consequences and appropriate input method. Use concise views, voice or simple
actions where suitable, and offer phone/tablet continuation when more context is needed. Full
editor, terminal, browser and emulator workflows are required on the Galaxy phone and tablet;
Wear does not require a full IDE or emulator qualification. Agent and human share the same
browser tabs and login state, with explicit
handoff of input ownership. A separate forwarded web preview does not satisfy that requirement.

Tablet acceptance includes keyboard, mouse, DeX, adaptive simultaneous work views and long work
sessions. Phone acceptance includes all core actions through touch. Device switching and network
interruptions preserve drafts and work context, and never silently repeat an uncertain operation.
Host or container failure must show interrupted work and permit controlled recovery; it does not
promise that a process survives the machine or container that owns it.

An available host means a previously configured, powered and reachable machine. This goal does
not imply waking an arbitrary powered-off machine or bypassing initial trust and pairing.

The earlier gap list below is a historical baseline, not a current capability inventory.
PocketPi `main` at `6836146` already includes host-worker opt-in, PR/CI presentation and worker
shell capability support. Neither this code inspection nor a build proves the qualifying run.
The host at NOAH `dev` `a1e18a22` defines `session.terminal.v1`, but does not yet serve its commands.

Implementation follows independently verified increments: host readiness and native terminal,
Android terminal interaction and recovery, remaining worker parity and direct development tools,
protected previews and shared browser interaction, emulator streaming and input, then full
device qualification across Mac, Linux and devcontainers. Each capability stays hidden until
the host actually implements it. Existing trust and Git-write decisions below remain binding.

Status: decisions taken, 2026-10-29. Baseline: PocketPi `main` at `f313a8b`, noah-monorepo at
`976824417` (`packages/pi-atlas/extensions/remote`).

## Yardstick

"Complete" means: whatever Robin does at the Mac with terminal pi, an editor and git, he can do
from the phone while nobody touches the Mac.

## Goal

**PocketPi carries a change from ticket to pull request entirely from the phone, with the Mac
unattended.**

The qualifying run: open a ticket, start work in a new session, follow the agent and answer every
question and approval it raises, review the diff, fix a file by hand, run the tests through a shell
command, commit, push and open a pull request, then see its CI result. The run counts only if
nobody touches the Mac.

## What already works

PocketPi `main` already covers most of the run:

- Pairing, shared projects, sessions, subagent children, fork, tree, reload, rename and export.
- Chat with quotes, dictation, attachments, slash commands, model, thinking and Advisor controls,
  steer and follow-up, compaction and host prompt suggestions.
- Questions, approvals, auto-mode escalations (#163) and terminal standard dialogs (#120, #131).
- Git status, diff and log against a session snapshot (`session.git.v1`, read-only).
- File browsing, saving text files (`session.files.write.v1`, on by default on the host), agent
  images and artifacts.
- Shell commands (`session.bash.v1`, off by default on the host) and background jobs. Shell
  commands work only in host-owned RPC sessions; see gap 6.
- Provider login (`provider.auth.v1`).
- Plane tickets (`tickets.v1`): reading is always on. Accepting, commenting, changing status and
  starting work need `config ticket-writes on` and Robin's own Plane key (`tickets set-key`) on
  the Mac. Starting a ticket creates a worktree, a branch and a session. Tickets in the Plane state
  "Review" show a pull request hint.
- Push notifications, the widget and the Wear OS companion.

Sessions started from the phone or from a ticket run as native PTY host workers and register as
`tui`. RPC sessions remain for reopened offline conversations, forks, hosts without node-pty and
the fallback when a worker does not start. A native session gets what a terminal session gets:
input is idle-only, and shell commands, follow-up and steer, export, tree and reload stay limited
to RPC sessions for now (remote README, "Phone-started sessions").

The agent, when asked in chat, can already run `git commit`, `git push`, `gh pr create`,
`gh pr checks` and `rg`, so the qualifying run can pass today through the agent. The gaps below are
about doing these steps as first-class, confirmed and logged actions.

## What was missing in the original baseline

This gap inventory is historical; the checkpoints above describe subsequent local implementation
and scoped qualification. The full acceptance run and its device/host gates remain open.

### Protocol and host (lands in noah-monorepo first)

1. **Git writes.** Dedicated selective commits are implemented locally in the host and app;
   push and pull request actions remain open. The app explicitly requests an own-checkout HEAD
   snapshot, excludes omitted files and submodules, and confirms the exact paths, branch and
   message. It persists an unresolved dispatch intent and never automatically retries it. This
   increment still needs real-host phone/tablet workflow qualification. Starting a ticket creates
   a branch and worktree (`tickets.v1`); read-only changes remain in `session.git.v1`, with mutations
   behind the separate owner-controlled `session.git.write.v1` switch. See "Decisions" for the rules.
2. **Pull request and CI status.** The app cannot see whether a session's branch has a pull
   request, what its checks say or what reviewers wrote. The ticket hint comes from the Plane state,
   not from GitHub.
3. **Oversized questions and approvals.** The confirm-details producer, Host and Android paths
   are implemented and independently reviewed, enabled by default with an explicit disable and
   per-connection opt-in. Four default native and external RPC end-to-end scenarios passed; broad
   The affected Host files passed their targeted rerun and the full Android/Wear suite passed;
   physical-device, independent Linux and production qualification remain open.
   Bounded RPC editor dialogs are implemented locally and default-enabled with explicit v3 opt-in; actual Host-owned Pi 1.4 to Android phone Submit and tablet Cancel passed. Local checks and independent reviews passed. Native and oversized/paged editors remain open. See [confirm qualification](testing/question-details-delivery-qualification.md) and [editor qualification](testing/remote-editor-qualification.md).
4. **Custom dialogs.** A relayed terminal is implemented locally, but the combined custom-dialog
   workflow remains unqualified. The earlier feasibility gate in
   noah-monorepo's `docs/superpowers/plans/2026-10-23-pocketpi-mobile-dialogs.md` failed on pi
   1.4.0: in RPC sessions the component is never created, and in terminal sessions direct input
   bypasses focus and input hooks. The plan names a separately designed terminal-control route as
   the way forward, so custom dialogs are answered through the relayed terminal (gap 7). Workspace
   and MCP trust prompts and local `/remote` administration are excluded from forwarding on
   purpose; the goal keeps that boundary.
5. **Code search.** The phone can browse and open files but has no search over contents or
   symbols outside the agent.
6. **Worker parity.** Worker shell commands, follow-up, steer, stop and queue clearing, close,
   tree and reload are implemented locally. Reopen and fork request workers when available, with
   RPC fallback. Export remains RPC-only. Combined workflow and physical-device qualification
   remain open; these implementations are not a universal parity claim.
7. **Relayed terminal.** The host worker terminal relay and app controls are implemented locally,
   including terminal input and output. The combined custom-dialog workflow and physical-device
   qualification remain open. This route covers host workers, not a pi Robin starts himself in
   his own Mac terminal.

### App only

8. **Host workers.** Host worker support and app opt-in are implemented locally, including the
   v2 capability. Combined workflow and physical-device qualification remain open.

### Later

- **Preview and port forwarding.** A dev server or web app running on the Mac cannot be opened on
  the phone.

## Decisions

Robin decided these on 2026-10-29. Each spec goes to Plane (TOOLING) as its own feature.

- **Pull request and CI status** (standard path): the wire carries the checks state, the failed
  count and up to five failing check names, without per-check links. A pill above the composer
  opens the Changes pane with "Open pull request" and "Open checks". The Ship menu leaves out its
  pull request prompt while a pull request is known. Offline sessions clear the status. Only open
  pull requests show. No push notification in the first version. The ticket fields follow as a
  separate ticket.
- **Oversized questions and approvals** (sensitive path): a question carries a bounded preview and
  a details ID only when its preview was cut. The phone fetches the details in pages, and Yes
  unlocks only after this connection fetched every page with a matching hash; scrolling to the end
  is not required. Details are capped at 1 MiB. Editor answers are capped at 64 KiB and ship as
  separate tickets after the details contract: first RPC sessions, then terminal sessions. The
  host also starts sending the escalation field. Dialog text from other extensions is not masked,
  like the chat. The audit is a `host.log` line, without a macOS notification.
- **Git writes** (sensitive path):
  - One Mac switch for commit, push and pull request, off by default.
  - Hooks are skipped, and repositories that use LFS are refused.
  - Robin does not sign commits. For a repository that requires signing, the host signs within
    20 seconds or refuses, and never commits unsigned.
  - Any session with its own folder may write, while every live session in that work tree is idle.
  - Protected branches block both commit and push. The list is configurable on the Mac and starts
    with the remote's default branch, `atlas.defaultBase`, `main`, `master`, `dev`, `develop`,
    `trunk`, `production`, `release/*` and `hotfix/*`.
  - Push always goes to `origin` under the same branch name and sets the upstream to it.
  - Pull requests use the Mac's `gh` login.
  - The phone shows the push target only as host and `owner/repo`. Local git config that
    redirects transport or credentials is refused, and only https and ssh push URLs are allowed.
- **Custom dialogs** go through the relayed terminal (gap 7), which moves into the goal.
- **Native workers are the one session runtime.** Every RPC-only feature comes to native worker
  sessions through the Remote extension, and reopened conversations and forks start workers too
  (gap 6). RPC stays only as the fallback for hosts without node-pty, so one path is maintained.
  Shell commands come first, because criterion 3 needs them.

## Acceptance criteria

Each criterion is checked in the qualifying run, on a real phone, with the Mac unattended.
"Implemented" means the code exists on `main`; "implemented locally" identifies changes not yet
established on `main`. Neither label by itself means the qualifying run has verified the criterion.

| # | Criterion | Where it lands | State |
|---|-----------|----------------|-------|
| 1 | Open a ticket and start a session from it | App, host switch and key | Implemented (`tickets.v1`, needs `config ticket-writes on` and `tickets set-key`) |
| 2 | Edit and save a file, with a clear conflict when the Mac changed it | App | Implemented (`session.files.write.v1`) |
| 3 | Run a shell command with live output and stop in the ticket's session | noah-monorepo, then app | Implemented locally for workers; combined ticket workflow and physical-device qualification open (gap 6) |
| 4a | Answer every forwarded question and approval from the phone, including oversized ones and editors | noah-monorepo, then app | Confirm-details implementation reviewed and enabled by default; native and external RPC scenarios passed, broad Host run non-green, corrected files passed targeted rerun; hardware/Linux/production qualification open; bounded RPC editors qualified locally on emulators, native/paged editors open (gap 3) |
| 4b | Answer custom dialogs from the phone through the relayed terminal | noah-monorepo, then app | One deterministic public native custom component qualified locally on phone/tablet emulators with confirmed takeover and exact result; arbitrary components, remote Linux and physical-device qualification remain open (gaps 4 and 7) |
| 5 | Commit, push and open a pull request, confirmed on the phone and logged on the Mac | noah-monorepo, then app | Commit, Push and PR implemented locally; full unattended workflow and physical-device qualification remain open |
| 6 | See the pull request and its CI status per session | noah-monorepo, then app | Display is wired; actual Pi through a Host-managed native worker to Android navigation passed on four emulator profiles. Live GitHub CI and physical-device qualification remain open. |

## Order

1. Run the qualifying run once, asking the agent for steps 5 and 6, and record where it breaks.
2. Shell commands in native sessions (criterion 3), then the rest of worker parity.
3. Pull request and CI status (criterion 6).
4. Git writes (criterion 5).
5. Oversized questions, approvals and editors (criterion 4a).
6. Relayed terminal and custom dialogs (criterion 4b).
7. Code search, then preview and port forwarding.

Each protocol step lands in noah-monorepo first, then in PocketPi with the upstream fixtures.
Device checks for each step (real phones, Samsung quirks, push delivery) are Robin's.

### Selective commit increment, 2026-10-31

App commit `44e5fde` adds the shared phone/tablet commit-review entry. Host commit `480c0ec9f`
adds the guarded executor, explicit review snapshot and isolated read-only remote transport;
`e0683b3c1` integrates the current upstream worker-close fix. None has been pushed or released.
The host Remote suite passed 3,422 tests with 35 skipped; later review/parser changes passed
68 focused tests and the upstream merge passed 35 focused tests plus TypeScript. The Android
full unit run passed 3,138 of 3,139 tests; its sole terminology failure was corrected, and both
terminology tests plus four commit-pane tests then passed. This is local evidence, not physical
Galaxy-device, independent Linux-host or complete ticket-to-PR/CI qualification.

### Encrypted Android selective-commit fixture, 2026-10-31

The selective commit pane now has a real encrypted Android-to-Mac fixture result in English and
German at phone and tablet display sizes: only the explicitly selected file entered the actual
commit and the second file stayed dirty. Screenshots were inspected; one stale English-tablet
confirmation capture was replaced after frame synchronization and a fresh fixture run. This uses
a synthetic idle session and targeted real-pane harness, not full navigation, a real Pi worker,
independent Linux or physical Samsung qualification. See
[the scoped qualification record](testing/selective-commit-qualification.md).

### Native/terminal PR-status bridge, 2026-10-31

The missing public `pr-status:status` event-bus connection is implemented locally, with bound-peer
Host ingestion and independent cache copies for registration/reconnect. An isolated real Pi 1.4.0
process drove the actual producer/parser and Remote extension; an encrypted simulated device
observed pending, named failed, passing and cleared status. This uses controlled gh JSON and an
external RPC Pi whose stdout is not consumed by the Host: it proves the bus/IPC path, not actual
GitHub CI. Subsequent separate fixtures passed the actual-Pi-to-Android navigation path on phone
and tablet emulator profiles and Host-managed native startup with a real PTY. All four English/German phone/tablet profile runs passed, and owned fixture cleanup was verified.
A subsequent combined Android-to-Host-managed native PTY/Pi TUI run passed all four English/German
phone/tablet emulator profiles, verifying the same owner and native worker, one cut and two upgrades.
It uses an injected spawn wrapper and flags; the external RPC default-mode regression also passed
and owned fixture/emulator/ADB cleanup was verified. See [the scoped qualification](testing/pr-status-parity-qualification.md).
Live GitHub CI, native input and model turns, MainActivity and production networking, physical devices
and an independent Linux testhost remain open.


The native command-delivery fixture subsequently passed on phone/tablet emulator profiles:
a command sent once through the production composer was verified in the same live Pi TUI
process/session by a nonce trace. This does not establish raw PTY input or a model turn.
A tablet IME-open overlap was traced in a bounded comparison to retained scroll; scrolling to
the top restores readable layout with and without IME. No production layout change was made; see the
[scoped record](testing/pr-status-parity-qualification.md).

### Native worker parity code audit, 2026-10-31

The current local code includes worker shell commands with output and stop, queued follow-up and
steer, queue clearing on stop, close, tree and reload, worker-first reopen and fork with RPC
fallback, and the relayed worker terminal. These are implementation findings; combined end-to-end
and physical-device qualification remain open. At that audit snapshot, native worker export was unavailable: the host
accepts export only for RPC sessions and the app gates it accordingly. Pi exposes a public
`AgentSession.exportToHtml` method, but this audit did not establish public extension access to the
already running worker's `AgentSession`. The public standalone CLI export is a possible separate
approach, not an implemented native export; session-file authority, races and its differences from
live-session export needed resolution before adoption. This historical gap is superseded by the
2026-11-01 native snapshot export checkpoint and qualification above.
