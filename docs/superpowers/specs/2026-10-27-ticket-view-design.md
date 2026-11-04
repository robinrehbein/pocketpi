# Ticket View Design

## Intent

Robin wants to keep up with his Plane tickets from the phone without opening Plane in a browser and
copying ticket text into a chat. Three needs drive this:

1. See what is going on: what waits for his acceptance, what is being worked on, what is next.
2. Accept and comment on tickets.
3. Hand a ticket to the agent in one step.

New tickets keep coming from the agent in chat; the app does not need a creation form.

This is the lean first version agreed after four critical reviews of an earlier, larger draft. The
clickable prototype (https://claude.ai/artifact/DhASH9Q2WD6CFAqyqyGbti) still shows the earlier
traffic-light card and board; use it for the ticket detail and sheets only.

## Goals

- A "Zur Abnahme" section on the home screen for tickets Robin can accept, and ticket refs on the
  existing session rows.
- A Tickets screen for one project: Zur Abnahme, Läuft, Todo, each limited to Robin's tickets.
- A ticket detail with comment, status change and accept.
- Start a ticket in a new agent session with its own worktree, from the phone.
- Show which sessions belong to which ticket, from now on.

## Non-goals (later, if still wanted)

- Board view, epics/modules progress, Backlog/Done counts.
- Handing a ticket to an existing session.
- CI/PR check state.
- Filtering or grouping the session list by ticket.
- Creating tickets in the app.
- Merging tickets across several paired Macs. The view is scoped to the connected Mac, like the
  Activity board.
- PROD and FND projects; cycles; push notifications for ticket changes.

## Anti-goals

- A second "needs you" list on home next to the existing one, or colours that contradict the app's
  session colours. Robin twice asked for a calm home screen.
- Robin's personal Plane key reaching any agent session, or code an agent can edit running with it.
- Ticket traffic that pushes agents into Plane's rate limit.
- Breaking installed apps or older hosts.

## Where the work lands

- noah-monorepo (`packages/pi-atlas/extensions/remote`, formerly `packages/pi-remote`): protocol,
  fixtures, host endpoints, sync, start job, user-key handling. Tracked as a TOOLING ticket, branch
  `task/tooling-<n>-…` against `dev`, following that repository's process (sensitive path: it
  handles a personal API key).
- PocketPi: fixture copies, UI, strings. Branch `feature/ticket-view` against `main`.
- Order: the host change merges and runs on the Mac first; the app only shows tickets when the host
  advertises the capability, so an app released first stays inert.

## User-facing design

### Home

The existing home sections stay. Changes:

- Session rows in "Braucht dich", "Arbeitet" and recent sessions show the linked ticket ref (e.g.
  `DEV-433`) as a small secondary line or chip.
- A new section **"Zur Abnahme"** appears above "Braucht dich" only when at least one ticket
  qualifies (decision table below). Each row: check-badge icon, `ref · title`, and one button
  `Abnehmen`. Tapping the row opens the ticket detail. At most three rows, then `Alle Tickets ›`.
  No summary text, no swipe pager, no extra colours: the icon and the word carry the meaning.
- When no row qualifies, the section is absent. While loading, the section is absent, never an
  empty "nothing to do" line. If tickets are unavailable (no configuration, Plane unreachable), the
  section is absent on home; the reason shows on the Tickets screen.
- Entry to the Tickets screen: the `Alle Tickets ›` link, and a `Tickets` item in the existing Mac
  overflow menu (always reachable, also when nothing awaits acceptance). No new top-bar icon.

### Tickets screen

- Top bar: back and title `Tickets`; reload. Below, the project name (only ticket-enabled projects
  exist here; with more than one, a simple chooser, none disabled).
- Sections: **Zur Abnahme**, **Läuft**, **Todo**. Rows show `ref`, title, and a caption with linked
  chats (`2 Chats · 1 arbeitet`) or `PR offen` for tasks in Review.
- States: loading skeleton; unavailable with a reason (`Plane nicht erreichbar`, `Tickets auf dem
  Mac nicht eingerichtet`, …); stale data shown with `Stand vor X Min` and write actions disabled
  while the Mac is offline.

### Ticket detail

- Header: back; `In Plane öffnen` (opens the ticket URL; only https on the configured Plane host).
- `project · ref`, title, pills for state, priority, type.
- Buttons: primary depends on the decision table (`Abnehmen`, `Mit Agent starten`, or none);
  secondary `Kommentar` and `Status ändern`.
- `Chats zu diesem Ticket` with status; tapping opens the session.
- Description and comments (newest first), loaded when the detail opens, rendered as markdown with
  http(s) links only.
- The chat header shows a ticket chip for linked sessions; tapping opens the ticket detail.

### Sheets

- **Abnehmen**: confirms `DEV-123 auf Done setzen` with a comment field prefilled with
  `Abgenommen in PocketPi.` (editable). Tapping `Abnehmen` closes the sheet and shows an undo bar
  (`DEV-123 wird abgenommen · Rückgängig`) for 5 seconds; only then does the app send the move.
  Undo cancels before anything reaches Plane, so the host needs no reverse move.
- **Kommentar**: text field; `Senden` disabled while empty.
- **Status ändern**: offers only the targets the decision table allows for this ticket; Done and
  Cancelled require a comment; Cancelled is visually secondary. Review is not offered (the agent
  moves tickets to Review with its evidence).
- **Mit Agent starten**: shows what will happen (worktree `task/dev-433-…`, ticket → In Progress,
  the prompt) and an optional note. `Starten` begins a job on the Mac; the sheet shows progress
  (preparing worktree → starting session → sending prompt) and opens the session when done. If a
  step fails, it says which and what already happened (e.g. worktree exists, ticket not moved).
  Offered only for `task`, `bug` and untyped tickets in Backlog, Todo or In Progress without a
  running linked session. If the worktree already exists, the sheet says so and the job reuses it.

All write actions are hidden when the Mac's `Tickets bearbeiten` setting is off or Robin's key is
missing; the detail then shows one line explaining why.

### Decision table

The host evaluates this table and sends each visible ticket's `section`, allowed `actions` and
`moveTargets`; the app renders them and holds no copy of the rules. Hidden tickets are not sent.
"Robin's ticket" means Robin is among the assignees; his member id is the owner of his Plane key
(`users/me`), falling back to the host's configured assignee when no key is set. Type comes
from the issue's `type` field (`type:` label); `null` when missing or ambiguous.

| State group / state | Type | Robin's? | Section | Primary action |
| --- | --- | --- | --- | --- |
| started / Review | feature, map | any | Zur Abnahme (home + screen) | Abnehmen |
| started / Review | other non-null, null | yes | Zur Abnahme | Abnehmen |
| started / Review | task, bug | yes | Läuft, caption `PR offen` | none (Done follows merge) |
| started / Review | task, bug, null | no | hidden | — |
| started / In Progress | any | yes, or has a linked session | Läuft | Zur Session if linked, else Mit Agent starten (task/bug/null) |
| unstarted / Todo | any | yes | Todo | Mit Agent starten (task/bug/null) |
| backlog | any | — | hidden | — |
| completed, cancelled | any | — | hidden | — |
| unknown state name | any | — | grouped by `state_group` | none |

Rows are evaluated top to bottom; the first match wins. Implementation tasks in Review are never
accepted in the app: their Done comes from the merge (pr-shepherd or `./noah done`). A waiting
linked session does not create a ticket alert; it already appears under "Braucht dich" with its
ticket ref.

## Data and Plane budget

### Reads (bot key, host-native, background sync)

- The host keeps a per-Plane-project snapshot (workspace + identifier, not per pi project) in
  memory. A sync runs on host start, every 5 minutes while at least one device has fetched tickets
  in the last 30 minutes, after the host's own writes (just the affected ticket), and on an explicit
  reload from the app, which is coalesced and rate-limited to once per 60 s.
- The host reads Plane itself over the REST API in TypeScript (the same endpoints `plane.sh` uses),
  so it controls and counts every request. It does not run `plane.sh`.
- One sync = one full issues scan per project (the API filters nothing server-side) plus states,
  labels and members cached for 6 hours. Budget: at most 25 requests per sync for DEV + TOOLING at
  today's size, measured in a test against a fake Plane.
- A host-wide token bucket limits all app-initiated Plane traffic to 20 requests per minute,
  leaving the rest of Plane's ~60/min to agents. Reads respect it; writes take priority over syncs.
- Detail data (description, comments) is fetched only when a detail opens (~9 requests), cached per
  ticket `updated_at`.
- No comments for lists, no modules, no PR lookups.
- The app never triggers a sync by itself beyond the explicit reload.

### Writes (Robin's key, host-native)

- Comment, move and the In Progress move during start use Robin's own Plane API key so Plane shows
  him as author.
- The key lives in the macOS Keychain through the host's existing `secret-store`, set with a host
  CLI command (`… tickets set-key`). It is never in `process.env`, never passed to `plane.sh`, never
  exported in a shell profile.
- The host performs these writes itself in TypeScript against the Plane REST API (resolve issue,
  resolve state, post comment, patch state). It does not execute `plane.sh` or any other code from
  the project checkout with this key.
- Before posting, the host re-reads the issue: it skips the PATCH if the state is already the
  target, and skips a comment identical to Robin's newest comment from the last 10 minutes. A write
  that times out after starting answers `outcome_unknown`; the app never retries writes
  automatically.
- Writes require the host setting `Tickets bearbeiten` (off by default, like file writes and bash).
- Refs from the app must match `^(P)-[1-9][0-9]{0,8}$` with `P` in the project's configured Plane
  projects; target states must be one of the named states the decision table allows.

### Configuration

- A shared project is ticket-enabled when its pi settings contain `atlas.plane.projects` and the
  project is trusted (existing rule in `shared/atlas-config.ts`). Only DEV and TOOLING are accepted
  in this version.
- Robin's Plane member id comes from host configuration (default from the existing `PLANE_MINE` /
  `NOAH_PLANE_ASSIGNEE` conventions).
- Reads use the bot key the host already imports as `PLANE_API_KEY`; base URL and workspace come
  from `atlas.plane.url` / `atlas.plane.workspace` (defaults `https://plane.join-noah.de`, `noah`).
- The Keychain store accepts only `[A-Za-z0-9_-]` secrets; the set-key command rejects other keys
  with a clear message.

## Session ↔ ticket links

- Derived, not stored: the host maps a session to a ticket from its working directory's branch with
  the plane extension's `branchRef(branch, configuredProjects)` (not noah-cli's hard-coded regex),
  and only keeps refs that exist in the snapshot.
- Evaluated when a session starts and when its working directory changes, not on every list call.
- Sessions started from the app's start job are on `task/<project>-<n>-…` branches, so they link
  automatically; so do sessions started on the Mac with "Start work" or `noah start`.
- Each ticket in results carries its linked sessions `{id, title, status}`, filtered through the
  same project and visibility checks as session lists. The app prefers its own live session status
  when it has the session.

## Start job

- Follows the `project.clone` job/status pattern: the app sends a start command, gets a job id, and
  polls status. Steps: fetch base, `git worktree add` under the project's configured `worktreesDir`
  on `task/<project>-<n>-<slug>` (reuse if present), move to In Progress (Robin's key, best effort,
  reported separately), create the session in the worktree via the host's existing `create(…,
  {cwd})`, send the prompt.
- Git runs with the environment `folders.ts` uses for clone (including `SSH_AUTH_SOCK`), so fetch
  works with SSH remotes.
- The host refuses to start if the configured `worktreesDir` and the directories it accepts for
  sessions disagree.
- The prompt asks the agent to prepare the checkout (install dependencies) and read the ticket
  first. No `pnpm install` on the host.
- Requires `Tickets bearbeiten`.

## Protocol rules

- New capability `tickets.v1`, advertised only on the `capabilities.v2:` route. The app sends no
  tickets command to a host without it.
- New commands in `remote/protocol/tickets.ts`, following `git.ts`/`files.ts`: list ticket-enabled
  projects (`tickets.projects`), list tickets of a project (snapshot, paged by an opaque host
  cursor), get ticket detail, comment, move, start job, start job status, reload.
- Nothing is added to `projects.list`, `Project` or `Session`; installed apps reject unknown fields.
- Results send `state` (string, bounded) plus `stateGroup` (Plane's fixed groups), `type`
  (nullable), `assignedToMe`, linked sessions, `url`, `updatedAt`. Optional fields reserved now:
  `pr`, `checks`. Unavailable reasons are a closed enum; the app maps any other value to a generic
  message. New fields after release go to `tickets.v2`.
- Expected failures are results with `available:false` and a reason; busy and rate-limited answer
  the existing `busy` error. Reads bypass the idempotency ledger and run off the device frame
  queue; writes go through the ledger.
- Size: list items carry no description; detail caps the description and the number of comments
  to stay under the 256 KiB plaintext limit; the start prompt stays under 128 KiB.
- New fixture `fixtures/tickets-v1.json` with valid and invalid cases, consumed by host tests and
  copied byte-for-byte into PocketPi's `src/test/resources`.

## App

- Gate on `tickets.v1`. Scope to the connected Mac.
- Refresh tickets on the home Refresh button and pull-to-refresh (explicit reload), on app resume
  (snapshot only, no reload), and after the app's own writes.
- Colours and tokens: reuse `RemoteColors.kt` meanings; the check-badge icon uses the theme's
  primary or tertiary role. No red/green pairing; every state also has text.
- Strings in a new `remote_tickets.xml` under `values/` and `values-de/`, with plurals via
  `pluralStringResource`. German copy follows the app glossary (`TerminologyTest`): no "Session",
  use the app's existing word for a chat; status words reuse `remote_status_*`.
- New `RemoteNavKey` entries for the Tickets screen and ticket detail; detail opens in the detail
  pane on two-pane layouts; snapshot restore follows existing rules.
- TalkBack: rows merge into one description (`DEV-433, zur Abnahme, Chat-Fehler …`), buttons
  labelled; touch targets ≥ 48 dp.
- Fix the outdated `packages/pi-remote` path in `CLAUDE.md` and `README.md`; add `.superpowers/` to
  `.gitignore`.

## Testing

- Host: fixture parsing; decision-table classification over all type/state/assignee combinations
  including null type and unknown states; request counts per sync against a fake Plane (budget);
  token bucket; user key never present in any spawned child environment; writes never execute
  checkout code; idempotent move/comment; start job steps, reuse of an existing worktree, partial
  failure reporting; branch-link detection with configured projects.
- App: fixture decoding (incl. invalid cases); capability gating; section assignment; sheets and
  validation; undo; unavailable/stale states; emulator flows for home → detail → accept and start.
- Checks per each repository's rules.
- Device checks for Robin: host under launchd runs `plane.sh` and reaches Plane; set the key with
  the CLI and accept a ticket from the phone (Plane shows Robin as author); a real start job over
  the relay on a slow network; TalkBack pass; home on the Samsung phone stays calm.

## Left to the builder

- Exact message shapes within the rules above, limits, sync cadence fine-tuning.
- The host CLI command name for setting the key and the setting's UI text on the Mac.
- Visual details, as long as home stays as calm as described.
