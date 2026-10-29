# Goal: PocketPi as a complete remote development environment

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
6. **Shell commands in native sessions.** `session.bash.v1` works only in RPC sessions, so the
   phone-started session of the qualifying run cannot run tests through `!` commands.
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
- **Shell commands** come to native worker sessions (gap 6).

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
2. Shell commands in native sessions (criterion 3).
3. Pull request and CI status (criterion 6).
4. Git writes (criterion 5).
5. Oversized questions, approvals and editors (criterion 4a).
6. Relayed terminal and custom dialogs (criterion 4b).
7. Code search, then preview and port forwarding.

Each protocol step lands in noah-monorepo first, then in PocketPi with the upstream fixtures.
Device checks for each step (real phones, Samsung quirks, push delivery) are Robin's.
