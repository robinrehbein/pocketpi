# Ticket View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Plane ticket view in PocketPi (home "Zur Abnahme", Tickets screen, detail, accept/comment/status, start a ticket in a new agent session), served by new host endpoints.

**Architecture:** Two repositories, two branches. Part A (noah-monorepo, `packages/pi-atlas/extensions/remote`) adds a `tickets` protocol module, a host-native Plane REST client with a token bucket, an in-memory snapshot that classifies tickets with the spec's decision table, user-key writes from the Keychain behind a host setting, and a start job modelled on `project.clone`. Part B (PocketPi) copies the fixture, adds a connection-scoped tickets repository API and loader, two navigation keys, the screens and sheets, and the home section. The host computes sections and allowed actions; the app only renders them.

**Tech Stack:** Part A: TypeScript on Node ≥ 24 with type stripping, vitest + `node:assert/strict`, biome. Part B: Kotlin, Jetpack Compose (M3), navigation3, kotlinx.serialization `JsonObject`, JUnit4 + Robolectric, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-10-27-ticket-view-design.md` (this repository).

## Global Constraints

- Capability `tickets.v1`, advertised only on the `capabilities.v2:` route of `projects.list`; never in `device.authenticated` or the v1 list. The app must add it to `routeCapabilities` (`DefaultRemoteRepository.kt:238-267`) or it is filtered out.
- Nothing is added to `projects.list`, `Project` or `Session` wire shapes.
- Strict parsing on both sides: unknown keys and enum values are rejected. Reserved optional fields now: `pr` (`{number, url}`) and `checks` (`"pass"|"fail"|"unknown"`) on ticket items; the app must accept them when present.
- Plaintext ≤ 256 KiB per frame: list items carry no description; detail caps description at 32 KiB (`descriptionTruncated`) and comments at 30 newest (`commentsTruncated`). Start prompt ≤ 128 KiB.
- Plane budget: app-initiated Plane requests ≤ 20 per minute host-wide (token bucket); one full sync of DEV + TOOLING ≤ 25 requests at today's size; states/labels/members cached 6 h; explicit reload coalesced, at most once per 60 s per Plane project.
- Robin's Plane key: Keychain account `plane-user-key` via `defaultSecretStore(...)` in `host/storage.ts`; never in `process.env`, never passed to a child process, never logged. Keys must match `/^[A-Za-z0-9_-]+$/` (Keychain constraint).
- Writes and the start job require host setting `ticketWrites` (config key, off when absent), toggled with the host CLI `config ticket-writes on|off`, mirroring `config file-writes`.
- Only Plane projects `DEV` and `TOOLING` are accepted; refs must match `^(DEV|TOOLING)-[1-9][0-9]{0,8}$` and belong to the project's `atlas.plane.projects`.
- Worktrees: the host only accepts sessions under `<project>/.worktrees` (`project-resolve.ts:26`); the start job refuses when `readAtlasConfig(...).worktreesDir` is not `.worktrees`.
- German UI copy: no "Session(s)" (TerminologyTest); use "Chat(s)"; status words reuse `remote_status_needs_you|working|ready`. Every string in `values/remote_tickets.xml` and `values-de/remote_tickets.xml`.
- noah-monorepo process: TOOLING ticket, branch `task/tooling-<n>-ticket-view` from `origin/dev`, PR against `dev`, sensitive path (personal API key): Robin's plan approval recorded on the ticket before implementation; `review-diff.ts` + reviewer before `pr-shepherd`. PocketPi: branch `feature/ticket-view`, PR against `main`.

## Review Focus

1. Plane unreachable / 401 / 429 during sync → the last snapshot stays served with its `syncedAt`; the list says `available:false` only when there has never been a snapshot. Test in Task A4.
2. A ticket changes between list and write (already Done, reassigned, deleted) → move re-reads, answers `not_found`/`conflict` or skips the PATCH; never posts the comment twice. Test in Task A6.
3. App killed or connection lost during the 5 s undo window → nothing is sent; the ticket is still in "Zur Abnahme" after reconnect. Test in Task B6.
4. Start job when the branch or worktree already exists, or when the Plane move fails → worktree reused, session still started, `moved:false` reported, sheet shows it. Test in Task A7 and B6.
5. Host without `tickets.v1`, or with the capability but `ticketWrites` off / no user key → app shows no ticket UI, respectively read-only UI with one explanatory line; never sends a write. Test in Task B1 and B6.

---

## Wire shapes (shared by Part A and Part B)

Commands (all carry `requestId`; `projectId` is the host's opaque shared-project id):

```
{type:"tickets.projects"}
{type:"tickets.list", projectId, cursor?}
{type:"tickets.get", projectId, ref}
{type:"tickets.reload", projectId}
{type:"tickets.comment", projectId, ref, text}                  text: 1..8000 chars
{type:"tickets.move", projectId, ref, state, comment?}          state: one of the item's moveTargets; comment required for Done/Cancelled, 1..2000 chars
{type:"tickets.start", projectId, ref, note?}                   note ≤ 2000 chars
{type:"tickets.start.status", startId}                          startId: 22-char base64url
```

Results:

```
{kind:"tickets.projects", items:[{projectId, name, planeProjects:[str], writable:bool, writeBlock?:"writes_off"|"missing_user_key", available:bool, reason?:Reason}]}
{kind:"tickets.list", projectId, available:true, syncedAt:int(ms), items:[Ticket], nextCursor?:str}
{kind:"tickets.list", projectId, available:false, reason:Reason}
{kind:"tickets.ticket", projectId, available:true, ticket: Ticket & {description:str, descriptionTruncated:bool, comments:[{id, author:str, createdAt:int, text:str}], commentsTruncated:bool}}
{kind:"tickets.ticket", projectId, available:false, reason:Reason}
{kind:"tickets.reloaded", projectId, syncedAt:int}
{kind:"tickets.commented", projectId, ref}
{kind:"tickets.moved", projectId, ref, state, skipped:bool}
{kind:"tickets.start.started", startId}
{kind:"tickets.start", startId, state:"running"|"succeeded"|"failed", step:"worktree"|"move"|"session"|"prompt"|"done", worktreeReused:bool, moved:bool|null, sessionId?, error?:"worktree_failed"|"session_failed"|"prompt_failed"|"timeout"|"not_supported"}

Reason = "not_configured" | "missing_bot_key" | "plane_unreachable" | "plane_rejected"
Ticket = {ref, title, url, state, stateGroup:"backlog"|"unstarted"|"started"|"completed"|"cancelled"|"triage",
          type:str|null, priority:"urgent"|"high"|"medium"|"low"|"none", assignedToMe:bool,
          section:"acceptance"|"working"|"todo", caption?:"pr_open",
          actions:[("accept"|"start"|"comment"|"move")...], moveTargets:[str...],
          updatedAt:int, sessions:[{id, title, status:"idle"|"running"|"waiting"|"offline"}],
          pr?:{number:int, url:str}, checks?:"pass"|"fail"|"unknown"}
```

Errors use existing codes: `busy` (rate limit, gates), `forbidden` (not shared/trusted, writes off), `not_found` (unknown ref, project, startId), `conflict` (move target no longer allowed), `invalid` for validation per existing parser behaviour; a write that times out after starting carries `outcomeReason:"outcome_unknown"`.

Decision table (host, first match wins; hidden rows are not sent):

| stateGroup / state | type | assignedToMe | section | actions | moveTargets |
| --- | --- | --- | --- | --- | --- |
| started / Review | feature, map | any | acceptance | accept, comment, move | Done, Cancelled |
| started / Review | other non-null except task, bug; or null | yes | acceptance | accept, comment, move | Done, Cancelled |
| started / Review | task, bug | yes | working, caption pr_open | comment | — |
| started / Review | anything else | no | hidden | | |
| started / In Progress | any | yes, or has linked session | working | comment, move, start* | Todo, Cancelled |
| started / other name | any | yes | working | comment | — |
| unstarted / any | any | yes | todo | comment, move, start* | In Progress, Backlog, Cancelled |
| other | | | hidden | | |

`start*` only for type task, bug or null, and only when no linked session is `running` or `waiting`. `accept` is the app's name for move-to-Done with the prefilled comment.

---

# Part A — noah-monorepo (host)

Working directory: a new worktree `.worktrees/task-tooling-<n>-ticket-view` from `origin/dev`, `pnpm install`. Paths below are relative to `packages/pi-atlas/extensions/remote/` unless absolute. Check command for every task: `pnpm --dir packages/pi-atlas run check:extensions && pnpm --dir packages/pi-atlas run test:extensions remote`.

### Task A0: Ticket and approval

- [ ] **Step 1:** After Robin approves this plan in chat, file the TOOLING ticket with `scripts/plane/plane.sh create-issue TOOLING --name "Ticket view for PocketPi: tickets.v1 host endpoints" --type task --label <area per types-labels-and-key.md> --description-file <spec summary>`; post a comment recording Robin's approval words and date.
- [ ] **Step 2:** Create the worktree and branch `task/tooling-<n>-ticket-view`; run `pnpm install`.

### Task A1: Protocol module and fixture

**Files:**
- Create: `protocol/tickets.ts`, `protocol/tickets_test.ts`, `protocol/fixtures/tickets-v1.json`
- Modify: `protocol/types.ts` (import types; extend `Command` at the union end `:645-647`, `ResultData` `:786-788`), `protocol/parse.ts` (imports `:5-6`; `parsePayload` switch near `:1386-1399`; `resultData` switch near `:1089-1101`), `protocol/mod.ts`, `protocol/README.md` (new `### Tickets` section after `### Project files` `:813`)

**Interfaces:**
- Produces: `TICKETS_CAPABILITY = "tickets.v1"`; types `TicketsCommand`, `TicketsResultData`, `Ticket`, `TicketReason`, `TicketSection`, `TicketAction`, `TicketStartState`; `TICKETS_COMMAND_TYPES`, `isTicketsCommand(c: {type: string}): c is TicketsCommand`, `parseTicketsCommand(value: Obj): void`, `parseTicketsResult(value: Obj): void`; limits `MAX_TICKET_COMMENT_CHARS = 8000`, `MAX_TICKET_MOVE_COMMENT_CHARS = 2000`, `MAX_TICKET_NOTE_CHARS = 2000`, `MAX_TICKET_DESCRIPTION_BYTES = 32 * 1024`, `MAX_TICKET_COMMENTS = 30`, `MAX_TICKETS_PAGE = 100`, `TICKET_REF = /^(DEV|TOOLING)-[1-9][0-9]{0,8}$/`.
- Follows the `protocol/git.ts` pattern exactly (`as const` enums, local `obj/str/opaqueId` validators, throw on failure).

- [ ] **Step 1: Write the fixture** `tickets-v1.json` in the `{version:1, note, valid:[{name,payload}], invalid:[...]}` format, with at least these valid names: `projects-command`, `projects-result`, `list-command`, `list-command-cursor`, `list-result`, `list-result-unavailable`, `list-result-reserved-fields` (item with `pr` and `checks`), `get-command`, `ticket-result`, `ticket-result-truncated`, `reload-command`, `reloaded-result`, `comment-command`, `commented-result`, `move-command-done`, `moved-result`, `start-command`, `start-started-result`, `start-status-command`, `start-running-result`, `start-succeeded-result`, `start-failed-result`; and invalid: `list-command-extra-field`, `get-command-bad-ref` (`PROD-1`), `get-command-ref-zero` (`DEV-0`), `comment-command-empty`, `comment-command-too-long`, `move-command-unknown-state`, `move-command-done-without-comment`, `list-result-unknown-section`, `list-result-unknown-reason`, `list-result-extra-item-field`, `start-status-bad-id`, `ticket-result-description-too-long`.
- [ ] **Step 2: Write `tickets_test.ts`** following `files_test.ts:19-52`: assert the fixture contains exactly the required names; for each valid item `assert.deepEqual(parsePayload(structuredClone(payload)), payload)`; for each invalid `assert.throws(() => parsePayload(payload))`.
- [ ] **Step 3: Run** `pnpm --dir packages/pi-atlas run test:extensions remote/protocol/tickets` → FAIL (module missing).
- [ ] **Step 4: Implement `protocol/tickets.ts`** and the wiring in `types.ts`, `parse.ts`, `mod.ts`. `move` with state `Done` or `Cancelled` requires `comment`; other states allow it optionally.
- [ ] **Step 5: Run** the protocol tests and `typecheck:extensions` → PASS. Also run `remote/protocol` to prove the existing fixtures still pass (old apps unaffected).
- [ ] **Step 6: Document** the commands, results, capability rule and limits in `protocol/README.md`.
- [ ] **Step 7: Commit** `Add tickets.v1 protocol module and fixture`.

### Task A2: Plane REST client with token bucket

**Files:**
- Create: `host/plane-api.ts`, `host/plane-api_test.ts`, `host/token-bucket.ts`, `host/token-bucket_test.ts`

**Interfaces:**
- Produces:
  - `class TokenBucket { constructor(perMinute: number, now?: () => number); take(priority: "write" | "read"): Promise<void>; tryTake(): boolean }` — writes jump the read queue; `take` rejects with `HostError("busy", ...)` when a read would wait > 30 s.
  - `interface PlaneApiOptions { baseUrl: string; workspace: string; key: () => Promise<string | undefined>; bucket: TokenBucket; fetch?: typeof fetch; timeoutMs?: number }` (default timeout 20 s).
  - `createPlaneApi(options): PlaneApi` with `projects(): Promise<{id, identifier}[]>`, `states(projectId)`, `labels(projectId)`, `members(projectId)`, `me(): Promise<{id: string}>`, `issuesPage(projectId, cursor?): Promise<{items: RawIssue[], next?: string}>` (per_page 100), `issue(projectId, issueId)`, `issueBySequence(projectId, seq)`, `comments(projectId, issueId)`, `postComment(projectId, issueId, html)`, `patchState(projectId, issueId, stateId)`.
  - `class PlaneHttpError extends Error { status: number }`; 401/403 → `"plane_rejected"`, network/timeout/5xx → `"plane_unreachable"`, 429 → `HostError("busy")`.
- Endpoints and headers: copy from `/scripts/plane/plane.sh` (`X-API-Key` header, `/api/v1/workspaces/<ws>/projects/...`, cursor paging). Every call goes through `bucket.take`.

- [ ] **Step 1: Tests** with a fake `fetch` that records calls: `issuesPageFollowsCursor`, `everyRequestTakesAToken`, `writesOvertakeQueuedReads`, `rateLimitedReadThrowsBusy` (bucket of 2/min, third read rejects within the 30 s rule using an injected clock), `http429MapsToBusy`, `http401MapsToPlaneRejected`, `keyNeverAppearsInErrors` (error messages and `String(error)` do not contain the key).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Add host Plane client with request budget`.

### Task A3: Ticket classification

**Files:**
- Create: `host/ticket-classify.ts`, `host/ticket-classify_test.ts`

**Interfaces:**
- Consumes: `Ticket`, `TicketSection`, `TicketAction` from A1.
- Produces: `classifyTicket(input: {state: string; stateGroup: string; type: string | null; assignedToMe: boolean; sessions: {status: string}[]}): {section: TicketSection; caption?: "pr_open"; actions: TicketAction[]; moveTargets: string[]} | null` (null = hidden); `issueType(labels: string[]): string | null` (exactly one `type:` label → its suffix, else null).

- [ ] **Step 1: Tests:** one test per decision-table row, plus `nullTypeInReviewMineIsAcceptance`, `nullTypeInReviewNotMineIsHidden`, `taskInReviewNeverOffersAccept`, `startHiddenWhenLinkedSessionRunning`, `startHiddenWhenLinkedSessionWaiting`, `startHiddenForFeature`, `unknownStartedStateIsWorkingWithoutMove`, `backlogHidden`, `doneHidden`, `issueTypeTwoLabelsIsNull`.
- [ ] **Step 2–4:** Run → FAIL; implement the table verbatim; run → PASS.
- [ ] **Step 5: Commit** `Classify tickets for the PocketPi view`.

### Task A4: Ticket snapshot service (reads, links, sync)

**Files:**
- Create: `host/tickets.ts`, `host/tickets_test.ts`

**Interfaces:**
- Consumes: A1 types, A2 `createPlaneApi`/`TokenBucket`, A3 `classifyTicket`/`issueType`; `ProjectRegistry` (`requireCurrent(id)`), `readAtlasConfig(root, {trusted: true})` from `../shared/atlas-config.ts`; `branchRef(branch, projects)` from `../plane/model.ts`; `Gate` from `host/gate.ts`; `HostError` from `host/state.ts`.
- Produces:
  - `interface TicketsOptions { projects: ProjectRegistry; sessions: { list(projectId: string, authorize: () => void): Promise<Session[]>; folder(sessionId: string): string | undefined }; botKey: () => string | undefined; userKey: () => Promise<string | undefined>; writable: () => boolean; assignee: () => string | undefined; api?: (cfg: {baseUrl: string; workspace: string}, key: () => Promise<string | undefined>) => PlaneApi; branch?: (folder: string) => Promise<string | undefined>; now?: () => number; bucket?: TokenBucket }`
  - `class Tickets { constructor(options: TicketsOptions); execute(command: TicketsCommand, deviceId: string, authorize: () => void): Promise<TicketsResultData>; stop(): void }` — this task implements `tickets.projects`, `tickets.list`, `tickets.get`, `tickets.reload`; write and start commands throw `HostError("unsupported")` until A6/A7.
- Behaviour:
  - Ticket-enabled project = shared + trusted + `atlas.plane.projects` non-empty and ⊆ {DEV, TOOLING}.
  - Snapshot keyed by `baseUrl|workspace|identifier`; a sync = metadata (cached 6 h) + all issue pages; stores raw issues and `syncedAt`. Background sync every 5 min while any `tickets.list` arrived in the last 30 min; `tickets.reload` coalesces with a running sync and is ignored (returns current `syncedAt`) within 60 s of the last sync.
  - On sync failure keep serving the previous snapshot; `available:false` only without any snapshot.
  - Links: for each session of the project, `folder(sessionId)`; if under `.worktrees/`, `branch(folder)` (cached 60 s per folder) → `branchRef(branch, planeProjects)` → keep if the ref exists in the snapshot.
  - `assignedToMe` = assignee id ∈ issue assignees; assignee from `options.assignee()` (A6 fills it from `users/me`).
  - List sorted by section (acceptance, working, todo) then `updatedAt` desc, paged by an opaque cursor (`base64url(offset|syncedAt)`; a cursor from an older snapshot → restart at 0).
  - `tickets.get` fetches detail and comments on demand, cached per `id|updated_at`, truncated per limits; comments rendered from `comment_stripped`/HTML to plain text.
  - Concurrency: per-device max 4 in flight, `Gate(4, 16)`; `busy` beyond.
- [ ] **Step 1: Tests** with a fake `PlaneApi` counting calls: `syncOfTwoProjectsStaysWithinBudget` (DEV 433 issues, TOOLING 120 → ≤ 25 requests including metadata), `metadataCachedAcrossSyncs`, `reloadWithin60sDoesNotSync`, `concurrentReloadsCoalesce`, `failedSyncServesLastSnapshot` (Review Focus 1), `noSnapshotAndPlaneDownIsUnavailable`, `projectWithoutPlaneConfigIsNotListed`, `untrustedProjectIsNotListed`, `prodProjectRejected`, `linksSessionByWorktreeBranch`, `ignoresBranchRefNotInSnapshot`, `listPagesWithOpaqueCursor`, `staleCursorRestarts`, `detailTruncatesDescriptionAndComments`, `hiddenTicketsAreNotSent`, `forbiddenWhenProjectUnshared`.
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS.
- [ ] **Step 5: Commit** `Serve a classified ticket snapshot to devices`.

### Task A5: Daemon wiring, capability, settings and CLI

**Files:**
- Modify: `host/daemon.ts` (imports `:19-22`, `:72-82`; field near `:622-624`; `HostRuntime` `:216-250` add `tickets?: Partial<TicketsOptions>`; construction near `:1994-2007`; routing in `executeOwned` `:3418-3437`; v2 capability list `:3474-3484` add `TICKETS_CAPABILITY` unconditionally; ledger bypass `:3512-3551` for `tickets.projects|list|get|start.status`, replay-only for writes while `ticketWrites` is off; off-queue list `:3611-3623` for all tickets commands; local command handler beside `file-writes` `:2435-2453` adding `ticket-writes`; status `:2865-2867`; shutdown `:3900`), `host/storage.ts` (`Configuration.ticketWrites?: boolean`, `ticketWritesSetting(value): {on: boolean}` off unless `true`), `host/cli.ts` (`config ticket-writes [on|off]` mirroring `:731-749`, `tickets set-key` reading the key from stdin, validating `/^[A-Za-z0-9_-]+$/`, storing account `plane-user-key`; `tickets clear-key`; usage strings), `README.md` (host setup: setting, key command, defaults)
- Create: `host/tickets_daemon_test.ts`

**Interfaces:**
- Consumes: A4 `Tickets`.
- Produces: daemon field `readonly tickets: Tickets`; `ticketWrites: boolean` passed as `writable: () => this.ticketWrites && hasUserKey`.
- `sessions` adapter: `list` = `this.sessions.list`, `folder` = `this.sessions.gitFolder(id)?.folder`.
- `botKey` = `() => process.env.PLANE_API_KEY`; `userKey` = `() => secrets().get("plane-user-key").catch(() => undefined)`.

- [ ] **Step 1: Daemon tests** (pattern of `files_daemon_test.ts`): `advertisesTicketsOnlyOnV2Route`, `v1RouteUnchanged` (entry count and content identical to before), `ticketsListRoutedOffQueue` (a slow list does not delay `session.abort`), `writesReplayOnlyWhileSettingOff`, `cliTicketWritesTogglesRunningHost`, `setKeyRejectsInvalidCharacters`, `userKeyNeverInChildEnv` (spawned RPC session env has no value equal to the stored key and no `PLANE_USER` name).
- [ ] **Step 2–4:** Run → FAIL; wire; run → PASS. Run the whole `remote` suite.
- [ ] **Step 5: Commit** `Wire tickets.v1 into the host`.

### Task A6: Writes with Robin's key

**Files:**
- Modify: `host/tickets.ts`, `host/tickets_test.ts`

**Interfaces:**
- Consumes: A2 client constructed with `options.userKey`; A3 to re-classify before a move.
- Produces: `tickets.comment`, `tickets.move` implemented; `options.assignee()` defaults to the cached `me().id` of the user key (fallback: host config `planeAssignee` if present, else undefined).
- Behaviour: reject with `forbidden` unless `writable()`; validate ref ∈ project; re-read the issue (bypassing cache); for move, re-classify and require `state ∈ moveTargets` else `conflict`; skip PATCH when already in target (`skipped:true`); post the comment unless Robin's newest comment has identical text within 10 min; comment HTML = escaped text with paragraphs per blank line; then PATCH; update the snapshot entry; a timeout after the first write request → `HostError(..., outcomeReason: "outcome_unknown")`.
- [ ] **Step 1: Tests:** `writesForbiddenWhenSettingOff`, `writesForbiddenWithoutUserKey`, `moveUsesUserKeyNotBotKey`, `moveRejectsTargetNotAllowed`, `moveSkipsPatchWhenAlreadyDone`, `moveDoesNotRepostSameComment` (Review Focus 2), `moveOnDeletedTicketIsNotFound`, `timeoutAfterCommentIsOutcomeUnknown`, `assigneeFromUserKeyOwner`, `commentEscapesHtml`, `writeUpdatesSnapshot`.
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS.
- [ ] **Step 5: Commit** `Comment on and move tickets as Robin`.

### Task A7: Start job

**Files:**
- Create: `host/ticket-start.ts`, `host/ticket-start_test.ts`
- Modify: `host/tickets.ts` (route `tickets.start`, `tickets.start.status`), `host/daemon.ts` (pass `createSession` and `prompt` hooks)

**Interfaces:**
- Consumes: `planWorktreeAdd`, `parseWorktreePorcelain`, `validateBranchName` from `../worktree/utils.ts`; `branchFor`, `startPrompt`, `START_HINT` from `../plane/start.ts`; `cloneEnv`, `gitRunner` from `host/folders.ts`; `randomId` from `host/state.ts`.
- Produces: `class TicketStarts { constructor(hooks: {git?: GitRunner; env?: () => NodeJS.ProcessEnv; createSession(projectId: string, cwd: string, authorize: () => void): Promise<Session>; prompt(sessionId: string, text: string, authorize: () => void): Promise<void>; move(projectId: string, ref: string): Promise<boolean>; now?: () => number; timeoutMs?: number}); start(input, deviceId, authorize): {startId}; status(startId, deviceId): TicketStartState; stop(): void }`. Max 2 concurrent starts (`busy`), records kept 1 h, max 32, status only for the starting device (`not_found` otherwise), timeout 10 min.
- Daemon hooks: `createSession` = `this.sessions.create(projectId, undefined, authorize, {cwd: realpath(cwd)})`; `prompt` = parse and execute `{type:"session.prompt", requestId: randomId(), sessionId, text}` through `this.sessions.execute` (pattern of `dispatchLocal` `daemon.ts:1295-1380`), retrying `busy` for up to 30 s while the new session boots.
- Steps in order: worktree (reuse an existing worktree for the branch; else `git -C <main> fetch origin` then `planWorktreeAdd(..., base = configuredDefaultBase)` under `<project>/.worktrees/<branch>`), move to In Progress (best effort, `moved` true/false), session, prompt. Prompt = `startPrompt(issue, START_HINT)` + `\n\nBefore you start, install the checkout's dependencies.` + optional `\n\nNote from Robin: <note>`.
- [ ] **Step 1: Tests** with a fake git runner and hooks: `startCreatesWorktreeSessionAndPrompt`, `startReusesExistingWorktree` (Review Focus 4), `moveFailureStillStartsSession` (`moved:false`), `sessionFailureReportsStep`, `refusesWhenWorktreesDirIsNotDotWorktrees`, `refusesWhenWritesOff`, `refusesWhenStartNotInActions`, `statusOnlyForStartingDevice`, `thirdConcurrentStartIsBusy`, `timeoutMarksFailed`, `gitEnvKeepsSshAuthSock`.
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS. Run the whole `remote` suite and `pnpm --dir packages/pi-atlas run test:extensions:integration`.
- [ ] **Step 5: Commit** `Start a ticket in a new worktree session`.

### Task A8: Review and ship (noah process)

- [ ] **Step 1:** Run `pnpm local-check` from the worktree; fix findings as new commits.
- [ ] **Step 2:** `scripts/agents/review-diff.ts` from the main checkout; brief `reviewer` with the diff path, `Revision: <head>`, intent = spec link. Fix findings as new commits; new round each time (cap 3).
- [ ] **Step 3:** Brief `pr-shepherd` (PR against `dev`, `Rulings` from this work). Do not merge.
- [ ] **Step 4:** Record the merged-or-head commit SHA of `protocol/fixtures/tickets-v1.json` for Part B.

---

# Part B — PocketPi (app)

Working directory: `/Users/robinrehbein/Code/pocketpi-ticket-view-design`, branch `feature/ticket-view`. Paths relative to `src/main/java/de/joinnoah/pi/remote/` unless absolute. Check command: `python3 scripts/i18n-check.py . && ./gradlew testDebugUnitTest assembleDebug lintDebug`.

### Task B1: Fixture, model, parsers, repository API

**Files:**
- Create: `Tickets.kt`, `src/test/java/de/joinnoah/pi/remote/TicketsTest.kt`, `src/test/resources/tickets-v1.json` (byte-for-byte copy from Part A, SHA from A8)
- Modify: `RemoteRepository.kt` (interface defaults), `DefaultRemoteRepository.kt` (`routeCapabilities` add `TICKETS_CAPABILITY`; implementations via `connectionRequest` `:5889`), `README.md:223-264` (fixture count "nineteen", provenance sentence, coverage list)

**Interfaces:**
- Produces in `Tickets.kt`: `internal const val TICKETS_CAPABILITY = "tickets.v1"`; enums with `wire`: `TicketSection`, `TicketAction`, `TicketReason`, `TicketStateGroup`, `TicketPriority`, `TicketWriteBlock`, `TicketStartStep`, `TicketStartError`, `SessionLinkStatus`; data classes `TicketProject(projectId, name, planeProjects, writable, writeBlock, available, reason)`, `TicketSession(id, title, status)`, `Ticket(ref, title, url, state, stateGroup, type, priority, assignedToMe, section, prOpen: Boolean, actions: Set<TicketAction>, moveTargets: List<String>, updatedAt, sessions, pr: TicketPr?, checks: String?)`, `TicketComment`, `TicketDetail(ticket, description, descriptionTruncated, comments, commentsTruncated)`, `TicketsPage(projectId, available, reason, syncedAt, items, nextCursor)`, `TicketStart(startId, state, step, worktreeReused, moved: Boolean?, sessionId, error)`; parsers `parseTicketProjects(data)`, `parseTicketsPage(data, projectId)`, `parseTicketDetail(data, projectId)`, `parseTicketMoved(data, projectId, ref)`, `parseTicketStartStarted(data)`, `parseTicketStart(data, startId)` using `Wire.keys` and throwing on any unknown key or enum; `canUseTickets(state: RemoteState): Boolean = state.connected && TICKETS_CAPABILITY in state.capabilities && TICKETS_CAPABILITY !in state.unavailableCapabilities`.
- `RemoteRepository` additions (defaults throw `RemoteRequestException.unsupported()`): `suspend fun ticketProjects(): List<TicketProject>`, `ticketsPage(projectId: String, cursor: String?): TicketsPage`, `ticketDetail(projectId: String, ref: String): TicketDetail`, `reloadTickets(projectId: String): Long`, `commentTicket(projectId: String, ref: String, text: String)`, `moveTicket(projectId: String, ref: String, state: String, comment: String?): Boolean` (returns `skipped`), `startTicket(projectId: String, ref: String, note: String?): String`, `ticketStartStatus(startId: String): TicketStart`. Each implementation first checks `canUseTickets` and throws `unsupported()` otherwise (an older host closes the connection on unknown commands).

- [ ] **Step 1: Tests** (pattern `GitChangesTest.kt`): `parsesEveryValidFixtureResult`, `rejectsEveryInvalidFixtureResult`, `sentCommandsMatchFixture` for every command (compare `JsonObject(sent - "requestId")`), `acceptsReservedOptionalFields`, `noTicketCommandWithoutCapability` (fake transport records zero `tickets.*` frames and the call throws unsupported — Review Focus 5), `capabilityKeptFromV2Route`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*TicketsTest*'` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Add tickets.v1 protocol support`.

### Task B2: Tickets state and loader

**Files:**
- Create: `TicketsLoader.kt`, `src/test/java/de/joinnoah/pi/remote/TicketsLoaderTest.kt`
- Modify: `RemoteRepository.kt` (`RemoteState.tickets: TicketsState = TicketsState()`; UI actions with no-op defaults: `fun refreshTickets(force: Boolean) {}`, `fun openTicket(projectId: String, ref: String) {}`, `fun queueAccept(projectId: String, ref: String, comment: String) {}`, `fun undoAccept() {}`), `DefaultRemoteRepository.kt` (create loader; call `refreshTickets(force = false)` when `TICKETS_CAPABILITY` first appears in the merge at `:1774-1798`, on app resume, and `force = true` from the home Refresh / pull-to-refresh paths), `NavigationFakeRepository.kt`

**Interfaces:**
- Produces: `data class TicketsState(val routeId: String? = null, val projects: List<TicketProject> = emptyList(), val pages: Map<String, TicketsPage> = emptyMap(), val loading: Boolean = false, val failure: TicketsFailure? = null, val detail: TicketDetail? = null, val detailLoading: Boolean = false, val pendingAccept: PendingAccept? = null, val start: TicketStart? = null)`; `enum class TicketsFailure { UNSUPPORTED, OFFLINE, BUSY, FORBIDDEN, NOT_FOUND, FAILED }` mapped like `GitChangesLoader.failure` (`:94-102`); `data class PendingAccept(projectId, ref, comment, deadline: Long)`; `fun acceptanceItems(state: TicketsState): List<Pair<TicketProject, Ticket>>` (section acceptance, all projects, sorted by `updatedAt` desc).
- `class TicketsLoader(scope, source: TicketSource, current: () -> RemoteState, now: () -> Long, update: ((RemoteState) -> RemoteState) -> Unit)` with `refresh(force)`, `loadDetail(projectId, ref)`, `queueAccept(...)` (sends after 5 000 ms unless `undo()`; cancels on connection loss — Review Focus 3), `undo()`, `comment(...)`, `move(...)`, `start(...)` (polls `ticketStartStatus` every 1 s until not running, max 10 min), dropping stale replies by a version counter and on route change.
- [ ] **Step 1: Tests** (virtual time): `refreshLoadsProjectsThenPages`, `forceRefreshCallsReloadThenList`, `acceptSendsAfterFiveSeconds`, `undoBeforeDeadlineSendsNothing`, `connectionLossDuringUndoWindowSendsNothing`, `startPollsUntilSucceeded`, `startReportsFailedStep`, `staleReplyAfterRouteChangeIgnored`, `busyMapsToBusyFailure`.
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS.
- [ ] **Step 5: Commit** `Load and hold ticket state`.

### Task B3: Navigation and entry points

**Files:**
- Modify: `RemoteNavigation.kt` (keys `Tickets(routeId: String)` and `Ticket(routeId: String, projectId: String, ref: String)` in the sealed interface `:68-102`; `selection()` `:104-114`; `isOverlay()` `:202-207`; `keptOverlays` `:262-270` like Providers; `pushOverHost` `:635-643`; `openTickets(routeId)`, `openTicket(routeId, projectId, ref)`; entries in `entryProvider` near `:1499`), `RemoteViewModels.kt` (`matches` `:21-34`; `TicketsViewModel(repository, key)` modelled on `ProvidersViewModel` `:230-258`), `RemoteScreens.kt` (title `:1383-1393`, `:2147-2152`, `:2722-2727`; MacOverflow `:4009-4067` new `tickets: (() -> Unit)?` item, tag `macTickets`, call site `:3146-3162` gated by `canUseTickets`), `AdaptiveLayout.kt` (`columnPanes` `:774-796`: both keys take the detail column like Providers)
- Test: `src/test/java/de/joinnoah/pi/remote/TicketsNavigationTest.kt`, extend `OverlayColumnsTest.kt`

- [ ] **Step 1: Tests:** `openTicketsPushesOverHost`, `openTicketFromTicketsPushesDetail`, `ticketKeysKeptAcrossRouteLikeProviders`, `ticketKeysUseDetailColumnInTwoPane`, `macOverflowShowsTicketsOnlyWithCapability`.
- [ ] **Step 2–4:** Run → FAIL; implement (screens can be placeholders until B4); run → PASS.
- [ ] **Step 5: Commit** `Add ticket navigation and Mac menu entry`.

### Task B4: Tickets screen and ticket detail

**Files:**
- Create: `TicketScreens.kt`, `src/main/res/values/remote_tickets.xml`, `src/main/res/values-de/remote_tickets.xml`, `src/test/java/de/joinnoah/pi/remote/TicketScreensTest.kt`

**Interfaces:**
- Consumes: B2 state, B3 keys/view model; `ProjectsSectionHeading`-style headings, `Section` card (`RemoteScreens.kt:4226`), skeleton pattern (`SkeletonBar` `:4218`), `MarkdownText` (http(s) links only), `openHttpsLink` (`ProviderAuthScreens.kt:102`) plus a host check: open only when the URL's host equals the host of the ticket-enabled project's Plane URL (derive from the first ticket URL's origin in the page; reject others).
- Produces: `@Composable fun TicketsScreen(key: RemoteNavKey.Tickets, model: TicketsViewModel, navigator: RemoteNavigator)`, `@Composable fun TicketDetailScreen(key: RemoteNavKey.Ticket, model: TicketsViewModel, navigator: RemoteNavigator)`.
- Content per spec sections "Tickets screen" and "Ticket detail": sections Zur Abnahme / Läuft / Todo; row caption from plurals `remote_tickets_chats_caption` ("%d Chat · %d arbeitet" / DE "%d Chat · %d arbeitet", with `other` forms) or `remote_tickets_pr_open`; stale line `remote_tickets_stale` when `now - syncedAt > 10 min` or disconnected; unavailable lines per `TicketReason`; generic message for unknown values. Detail: pills, linked chats (tap → `RemoteNavKey.Chat`), description, comments, `In Plane öffnen`; buttons only for `actions` present and `writable`; one line from `writeBlock` otherwise.
- [ ] **Step 1: Robolectric tests:** `sectionsInOrder`, `emptyLoadingShowsSkeletonNotEmptyText`, `unavailableShowsReason`, `staleLineWhenOld`, `detailButtonsFollowActions`, `readOnlyLineWhenWritesBlocked`, `planeLinkOnlyForPlaneHost`, `germanCopyPassesGlossary` (run existing `TerminologyTest`).
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS (`python3 scripts/i18n-check.py .` too).
- [ ] **Step 5: Commit** `Show tickets and ticket details`.

### Task B5: Home section, chat rows and chat header chip

**Files:**
- Modify: `RemoteScreens.kt` (insert the "Zur Abnahme" block before `:2772`, after `val recentChats = quick.recent` `:2771`; chat header chip in the pill row `:1508-1535`, adjusting `maxTitleWidth` `:1461-1464`), `SessionList.kt` (ticket ref in the meta row `:281-297` and in `rowDescription` `:127`), `SessionListPresentation.kt` (`SessionListItem.ticketRef: String?` filled from a lookup, not from the session JSON)
- Test: extend `MacHubUiTest.kt`

**Interfaces:**
- Consumes: `acceptanceItems(state.tickets)`; a lookup `ticketRefForSession(state.tickets, sessionId): String?` (add to `Tickets.kt`: first ticket whose `sessions` contains the id).
- Section: heading `remote_tickets_acceptance` ("Zur Abnahme"), up to 3 rows (check-badge icon `Icons.Outlined.Verified` or the closest existing icon, `ref · title`, one `Abnehmen` text button when `accept ∈ actions` and writable, row tap → `openTicket`), then `remote_tickets_all` link → `openTickets`. Absent while loading, when empty, or without capability. Colours from `MaterialTheme.colorScheme.primary`; no new status colours.
- [ ] **Step 1: Tests:** `acceptanceSectionAboveNeedsYou` (use `headingTop`), `acceptanceSectionAbsentWhenEmpty`, `acceptanceSectionAbsentWhileLoading`, `atMostThreeRowsThenAllLink`, `chatRowShowsTicketRef`, `chatHeaderChipOpensTicket`, `talkBackRowDescriptionIncludesRef`.
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS.
- [ ] **Step 5: Commit** `Show tickets awaiting acceptance on home`.

### Task B6: Sheets: accept with undo, comment, status, start

**Files:**
- Create: `TicketSheets.kt`, `src/test/java/de/joinnoah/pi/remote/TicketSheetsTest.kt`
- Modify: `TicketScreens.kt`, `RemoteScreens.kt` (an undo bar host on the Projects page and ticket screens showing `pendingAccept`), string files

**Interfaces:**
- Consumes: B2 loader actions via the view model.
- Produces: `AcceptSheet`, `CommentSheet`, `StatusSheet`, `StartSheet` (`ModalBottomSheet` like `AdvisorPickerSheet.kt:29-54`), `TicketUndoBar(pending: PendingAccept?, onUndo: () -> Unit)`.
- Accept: comment prefilled `remote_tickets_accept_comment` ("Accepted in PocketPi." / "Abgenommen in PocketPi."), confirm → `queueAccept`, sheet closes, undo bar `remote_tickets_accepting` with `remote_tickets_undo` for 5 s. Comment: send disabled while blank. Status: chips = `moveTargets` minus `Done` (Done goes through accept); `Cancelled` styled secondary; comment required for Cancelled. Start: shows branch preview `task/<ref lowercase>-…`, "Ticket → In Progress", note field; `Starten` → `start`; progress lines per `TicketStartStep`; on succeeded open `RemoteNavKey.Chat(routeId, projectId, sessionId)`; on failed show the step and `worktreeReused`/`moved` facts.
- No automatic retry of any write; a write failing with `outcome_unknown` shows `remote_tickets_outcome_unknown` and refreshes the ticket.
- [ ] **Step 1: Tests:** `acceptQueuesAndShowsUndo`, `undoRemovesBarAndSendsNothing`, `commentSendDisabledWhenBlank`, `statusSheetOmitsDoneAndRequiresCommentForCancelled`, `startShowsProgressThenOpensChat`, `startFailureShowsStepAndFacts` (Review Focus 4), `writeButtonsAbsentWhenNotWritable` (Review Focus 5), `outcomeUnknownShowsMessageAndRefreshes`.
- [ ] **Step 2–4:** Run → FAIL; implement; run → PASS.
- [ ] **Step 5: Commit** `Accept, comment, change status and start tickets`.

### Task B7: Emulator flow, docs and branch review

**Files:**
- Modify: `src/androidTest/java/de/joinnoah/pi/remote/RemoteNavigationUiTest.kt` (`UiRemoteRepository` `:2171` gains ticket state and actions), `README.md` (feature note if the README lists features)

- [ ] **Step 1:** Instrumentation test `ticketAcceptFlow`: Mac → Projects shows "Zur Abnahme" → tap row → detail → Abnehmen → undo bar → wait → ticket gone. And `ticketsFromMacMenu`.
- [ ] **Step 2:** Run `./gradlew pixel2Api36DebugAndroidTest` if `/dev/kvm` is available; otherwise report "not run locally, CI covers it".
- [ ] **Step 3:** Run the full check command; fix and commit.
- [ ] **Step 4:** One independent reviewer subagent over `git diff origin/main...HEAD` with the spec; fix findings as commits.
- [ ] **Step 5:** Push the branch and open a PR against `main` (draft until Part A is merged and running on the Mac). Do not merge.

## Device checks for Robin (after both PRs)

- `tickets set-key` with a key for his own Plane account; `config ticket-writes on`.
- Host under launchd: Tickets screen loads for noah-monorepo.
- Accept a feature in Review from the phone; Plane shows Robin as author.
- Start a Todo task from the phone over the relay; worktree session appears and the agent starts.
- Home on the Samsung phone stays calm; TalkBack pass on the new rows.
