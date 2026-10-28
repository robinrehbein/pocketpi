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
- Questions, approvals, auto-mode escalations and terminal standard dialogs (`feat/mobile-dialogs`,
  `feat/dev-1762-escalations`).
- Git status, diff and log against a session snapshot (`session.git.v1`, read-only).
- File browsing, saving text files (`session.files.write.v1`), agent images and artifacts.
- Shell commands in phone-started sessions (`session.bash.v1`, off by default on the host) and
  background jobs.
- Provider login (`provider.auth.v1`).
- Plane tickets: read, accept, comment, change status and start work (`tickets.v1`).
- Push notifications, the widget and the Wear OS companion.

## What is missing

### Protocol and host (lands in noah-monorepo first)

1. **Git writes.** No stage, commit, push or pull request. Starting a ticket with an agent names a
   branch, but otherwise `session.git.v1` is read-only by design: the phone never sends a ref, an
   object ID or a free path. A write contract needs the same
   discipline: host-chosen refs, an explicit confirmation on the phone and a `host.log` line per
   write, behind a host switch like `file-writes`.
2. **Pull request and CI status.** The app cannot see whether a session's branch has a pull
   request, what its checks say or what reviewers wrote.
3. **Oversized approvals.** A command, reason or identity beyond the display bounds shows "Phone
   approval unavailable"; the remote README names the missing piece as "a future details contract
   and UI change".
4. **Custom dialogs.** `ui.custom` and editor dialogs still need the Mac. The feasibility gate in
   noah-monorepo's `docs/superpowers/plans/2026-10-23-pocketpi-mobile-dialogs.md` failed on pi
   1.4.0, so this needs a revised architecture or pi support.
5. **Code search.** The phone can browse and open files but not search their contents or symbols.

### App only

6. **Host workers.** The host advertises `session.host_worker.v1`; the app does not opt in, so
   workers appear as `tui` sessions.

### Second stage

These are not needed for the ticket-to-pull-request run and are the most expensive protocol
additions, so they follow the goal instead of being part of it:

- **Interactive terminal.** `view.native.*` drives host worker terminals over the local socket
  only, not through the relay.
- **Preview and port forwarding.** A dev server or web app running on the Mac cannot be opened on
  the phone.

### Product

- `.github/workflows/release.yml` runs only on `workflow_dispatch`; automatic publishing to the
  Play testing tracks stays pending until `docs/runbooks/play-testing-release.md` is complete.

## Acceptance criteria

Each criterion is checked in the qualifying run, on a real phone, with the Mac unattended.

| # | Criterion | Where it lands | State |
|---|-----------|----------------|-------|
| 1 | Open a ticket and start a session from it | App | Done (`tickets.v1`) |
| 2 | Edit and save a file, with a clear conflict when the Mac changed it | App | Done (`session.files.write.v1`) |
| 3 | Run a shell command with live output and stop | App, host switch | Done (`session.bash.v1`, needs `config bash-commands on`) |
| 4 | Answer every question and approval from the phone, including oversized ones and custom dialogs | noah-monorepo, then app | Open (gaps 3 and 4) |
| 5 | Stage, commit, push and open a pull request with a chosen branch, confirmed on the phone and logged on the Mac | noah-monorepo, then app | Open (gap 1) |
| 6 | See the pull request and its CI status per session | noah-monorepo, then app | Open (gap 2) |

## Order

1. Run the qualifying run once with criteria 1 to 3 and record where it breaks.
2. Git writes (criterion 5): protocol contract and host in noah-monorepo, then the app.
3. Pull request and CI status (criterion 6), same order.
4. Oversized approvals and custom dialogs (criterion 4).
5. Code search and host workers.
6. Second stage: relayed terminal, then preview and port forwarding.

Device checks for each step (real phones, Samsung quirks, push delivery) are Robin's.
