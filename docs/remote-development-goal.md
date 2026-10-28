# Goal: PocketPi as a complete remote development environment

Status: proposed, 2026-10-28. Baseline: PocketPi `main` at `f313a8b`, noah-monorepo at
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
- Shell commands in phone-started sessions (`session.bash.v1`, off by default on the host) and
  background jobs.
- Provider login (`provider.auth.v1`).
- Plane tickets (`tickets.v1`): reading is always on. Accepting, commenting, changing status and
  starting work need `config ticket-writes on` and Robin's own Plane key (`tickets set-key`) on
  the Mac. Starting a ticket creates a worktree, a branch and a session. Tickets in the Plane state
  "Review" show a pull request hint.
- Push notifications, the widget and the Wear OS companion.

With `bash-commands` on, the shell (or the agent, when asked in chat) can already run
`git commit`, `git push`, `gh pr create`, `gh pr checks` and `rg`. The qualifying run can therefore
pass today through the shell. The gaps below are about doing these steps as first-class, confirmed
and logged actions instead of free-form commands.

## What is missing

### Protocol and host (lands in noah-monorepo first)

1. **Git writes.** There is no dedicated stage, commit, push or pull request action. Starting a
   ticket creates a branch and worktree (`tickets.v1`); `session.git.v1` is read-only by design:
   the phone never sends a ref, an object ID or a free path. A write contract needs the same
   discipline: host-chosen refs, an explicit confirmation on the phone, a `host.log` line per write
   and a host switch like `bash-commands`.
2. **Pull request and CI status.** The app cannot see whether a session's branch has a pull
   request, what its checks say or what reviewers wrote. The ticket hint comes from the Plane state,
   not from GitHub.
3. **Oversized questions and approvals.** A command, reason or identity beyond the display bounds
   shows "Phone approval unavailable"; the remote README names the missing piece as "a future
   details contract and UI change". An oversized standard question also stays on the Mac.
4. **Dialogs that still need the Mac.** `ui.custom` and multiline editor dialogs still need the
   Mac. The feasibility gate in noah-monorepo's
   `docs/superpowers/plans/2026-10-23-pocketpi-mobile-dialogs.md` failed on pi 1.4.0, so custom
   dialogs need a revised architecture or pi support. Workspace and MCP trust prompts and local
   `/remote` administration are excluded from forwarding on purpose; the goal keeps that boundary,
   so the qualifying run must not depend on them. Terminal processes started before an update need
   `/reload` or a restart before they forward dialogs.
5. **Code search.** The phone can browse and open files but has no search over contents or
   symbols outside the shell.

### App only

6. **Host workers.** The host advertises `session.host_worker.v1`; the app does not opt in, so
   workers appear as `tui` sessions.

### Second stage

These are not needed for the ticket-to-pull-request run and are the most expensive protocol
additions, so they follow the goal instead of being part of it:

- **Interactive terminal.** `view.native.*` drives host worker terminals over the local
  `host.sock` view protocol only, not through the relay.
- **Preview and port forwarding.** A dev server or web app running on the Mac cannot be opened on
  the phone.

## Acceptance criteria

Each criterion is checked in the qualifying run, on a real phone, with the Mac unattended.
"Implemented" means the code exists on `main`; no qualifying run has verified it yet.

| # | Criterion | Where it lands | State |
|---|-----------|----------------|-------|
| 1 | Open a ticket and start a session from it | App, host switch and key | Implemented (`tickets.v1`, needs `config ticket-writes on` and `tickets set-key`) |
| 2 | Edit and save a file, with a clear conflict when the Mac changed it | App | Implemented (`session.files.write.v1`) |
| 3 | Run a shell command with live output and stop | App, host switch | Implemented (`session.bash.v1`, needs `config bash-commands on`) |
| 4 | Answer every forwarded question and approval from the phone, including oversized ones and custom dialogs | noah-monorepo, then app | Open (gaps 3 and 4) |
| 5 | Stage, commit, push and open a pull request with a chosen branch, confirmed on the phone and logged on the Mac | noah-monorepo, then app | Open (gap 1); possible today through the shell |
| 6 | See the pull request and its CI status per session | noah-monorepo, then app | Open (gap 2); possible today through the shell |

## Order

1. Run the qualifying run once, using the shell for steps 5 and 6, and record where it breaks.
2. Git writes (criterion 5): protocol contract and host in noah-monorepo, then the app.
3. Pull request and CI status (criterion 6), same order.
4. Oversized questions, approvals and custom dialogs (criterion 4).
5. Code search and host workers.
6. Second stage: relayed terminal, then preview and port forwarding.

Device checks for each step (real phones, Samsung quirks, push delivery) are Robin's.
