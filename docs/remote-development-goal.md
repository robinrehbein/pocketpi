# Goal: PocketPi as a complete remote development environment

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

The qualifying workflow includes an interactive Android emulator, Wear OS emulator and an iOS
simulator on a Mac. Agent and human share the same browser tabs and login state, with explicit
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

## What is missing

### Protocol and host (lands in noah-monorepo first)

1. **Git writes.** There is no dedicated stage, commit, push or pull request action. Starting a
   ticket creates a branch and worktree (`tickets.v1`); `session.git.v1` is read-only by design:
   the phone never sends a ref, an object ID or a free path. See "Decisions" for the agreed rules.
2. **Pull request and CI status.** The app cannot see whether a session's branch has a pull
   request, what its checks say or what reviewers wrote. The ticket hint comes from the Plane state,
   not from GitHub.
3. **Oversized questions and approvals.** A command, reason or identity beyond the display bounds
   shows "Phone approval unavailable"; the remote README names the missing piece as "a future
   details contract and UI change". An oversized standard question also stays on the Mac, and
   multiline editor dialogs need a protocol change.
4. **Custom dialogs.** `ui.custom` dialogs still need the Mac. The feasibility gate in
   noah-monorepo's `docs/superpowers/plans/2026-10-23-pocketpi-mobile-dialogs.md` failed on pi
   1.4.0: in RPC sessions the component is never created, and in terminal sessions direct input
   bypasses focus and input hooks. The plan names a separately designed terminal-control route as
   the way forward, so custom dialogs are answered through the relayed terminal (gap 7). Workspace
   and MCP trust prompts and local `/remote` administration are excluded from forwarding on
   purpose; the goal keeps that boundary.
5. **Code search.** The phone can browse and open files but has no search over contents or
   symbols outside the agent.
6. **Worker parity.** `session.bash.v1` works only in RPC sessions, so the phone-started session
   of the qualifying run cannot run tests through `!` commands. Follow-up and steer while pi runs,
   clearing the queue on stop, closing the session, export, tree, reload and keeping the Mac awake
   are RPC-only as well, and reopened conversations and forks still start RPC sessions.
7. **Relayed terminal.** `view.native.*` drives host worker terminals over the local `host.sock`
   view protocol only, not through the relay. Relaying it lets the phone operate a worker's full
   terminal, including custom dialogs. It covers sessions the host runs as workers, not a pi Robin
   starts himself in his own Mac terminal.

### App only

8. **Host workers.** The host advertises `session.host_worker.v1`; the app does not opt in, so
   workers appear as `tui` sessions. In review as PocketPi #168.

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
"Implemented" means the code exists on `main`; no qualifying run has verified it yet.

| # | Criterion | Where it lands | State |
|---|-----------|----------------|-------|
| 1 | Open a ticket and start a session from it | App, host switch and key | Implemented (`tickets.v1`, needs `config ticket-writes on` and `tickets set-key`) |
| 2 | Edit and save a file, with a clear conflict when the Mac changed it | App | Implemented (`session.files.write.v1`) |
| 3 | Run a shell command with live output and stop in the ticket's session | noah-monorepo, then app | Open (gap 6); works today only in RPC sessions |
| 4a | Answer every forwarded question and approval from the phone, including oversized ones and editors | noah-monorepo, then app | Open (gap 3) |
| 4b | Answer custom dialogs from the phone through the relayed terminal | noah-monorepo, then app | Open (gaps 4 and 7) |
| 5 | Commit, push and open a pull request, confirmed on the phone and logged on the Mac | noah-monorepo, then app | Open (gap 1); possible today through the agent |
| 6 | See the pull request and its CI status per session | noah-monorepo, then app | Open (gap 2); possible today through the agent |

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
