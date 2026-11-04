# Structured session activity (`session_activity.v1`)

Implementation and contract note for PocketPi. The Android implementation is available in
[Draft PR #242](https://github.com/robinrehbein/pocketpi/pull/242). Local checks and independent
reviews passed. The latest integrated main revision is recorded below; validation results and CI for
the current head are tracked on the PR. Real-device checks are pending (Robin).

## Host source

- Pinned host: `noah-financial-services-gmbh/noah-monorepo` (local checkout `noah-org`), `origin/dev` at
  `e63f650fb4080a1f2ecc1b6d7de8245bf6e736ab`. It contains the original activity commit
  `9de23b7641ba8103785e528e9c54716497335876` (TOOLING-1232 to TOOLING-1236).
- The commit is on `dev`, not on `main`. A host built from `main` (or any host without the feature)
  does not grant the capability and uses the legacy path, so the legacy
  path (no activity fields, no `session.activity` events) is mandatory and must keep working
  unchanged.
- Contract sources, under `packages/pi-atlas/extensions/`:
  - `remote/protocol/activity.ts`: types, validator, producer and receiver helpers
  - `remote/protocol/activity_test.ts`: ordering and freshness vectors
  - `remote/protocol/fixtures/session-activity-v1.json`: wire and opt-in fixture, copied to
    `src/test/resources`
  - `remote/protocol/README.md`: section "Structured session activity"
  - `remote/protocol/types.ts`, `remote/protocol/parse.ts`
  - Host wiring: `remote/host/activity-projection.ts`, `remote/host/sessions.ts`,
    `remote/host/daemon.ts`, `remote/runtime-activity.ts`, `subagent/src/remote-activity.ts`
  - Renderer reference: `shared/status-glyph.ts`, `shared/glyph-frames.ts`

Real host wiring was verified by reading that source: capability answer, bridge grant, probe and
lease, projection for legacy peers, and the Agent card fields.

## Negotiation

- Opt in on every connection through the v3 route: the `projects.list` request ID is
  `capabilities.v3:optin=<names>:<nonce>`, where `<names>` contains `session_activity.v1` (other
  names joined with `+`). A v2 request never grants it.
- Granted only when the answer's `capabilities` lists `session_activity.v1`. The grant lasts for that
  connection; a later request without the opt-in does not revoke it.
- A new connection starts ungranted. Until it opts in again it receives no activity fields and no
  `session.activity` events.

## Wire fields

`ActivitySnapshot`: `clockId`, `epoch`, `seq` (non-negative safe integer) and `state`:

- `active`: `expiresAt` (absolute, host monotonic ms), `phase` (`pending`, `thinking`, `writing`,
  `tool`), optional `thinkingLevel`, `tools`, optional `toolsOmitted`.
- `idle`: explicit clear. `unknown`: the host cannot tell; never means finished. Neither carries
  other keys. `null` is invalid; unknown keys are invalid.

Where it appears (all optional):

| Source | Fields | May set a new epoch or clock |
|---|---|---|
| `sessions` result | `items[].activity`, `activityClock` (required when any item has activity) | yes |
| `snapshot`, first page only | `activity`, `activityClock` | yes |
| `pong` | `activityClock` | no (clock sample) |
| `session.events` replay | events, `activityClock` | no |
| live `session.activity` event | `activity` | no |
| Agent card in `message.upsert` | `instanceId`, `spawnToolCallId`, `executionActivity`, `needsInput` | see receiver policy |

A missing `activity` means unknown, never idle. The host leaves it out for offline and saved
sessions and may drop it from list items to stay within the size limit.

## Exact IDs and omitted tools

- Tools are `{toolCallId, name, parentToolCallId?}`. IDs are 1 to 256 UTF-8 bytes without
  whitespace or control characters and are unique per snapshot. Names are 1 to 64 bytes.
- Match tools by `toolCallId` only, never by name. Parallel tools with the same name are distinct.
  `parentToolCallId` only records nesting.
- At most 128 tools are listed. `toolsOmitted` (1 to 1,000,000) counts the rest. `phase = tool` with
  empty `tools` and a positive `toolsOmitted` is valid. Show the count, never invent names.
- Agent rows match by `instanceId` (stable across resumes, distinct for same-name agents) and attach
  only to the card whose tool call ID equals `spawnToolCallId` in the same session. Never match by
  display name.

## Clock and freshness

- `clockId` is random per daemon process. A daemon restart means a new clock and a new baseline.
- Before each authoritative request (first snapshot page, session list, ping) read
  `SystemClock.elapsedRealtime()` as `requestStart`, with a `requestSeq` that grows per connection.
- Baseline offset: `requestStart - sampledAt`, keeping the largest value per connection and clock.
- Live only while: state `active`, view and baseline from the current connection, matching
  `clockId`, and `now < expiresAt + offset`. Remaining time is capped at 30 s.
- Expiry freezes the last state as stale. It never turns into idle or done. The transport heartbeat
  renews nothing.
- Pure presentation helpers do not read an Android clock through default arguments. Production
  Compose callers supply monotonic time explicitly. Without it, negotiated activity stays static
  and never falls back to transcript inference; legacy behavior is unchanged.
- Observed host internals, not contract (rely only on `expiresAt`, the offset and the 30 s cap):
  lease 15 s from probe start, probe answers valid within 5 s, owner hint every 5 s, renewal
  events once less than half the lease is left (about every 7 to 8 s while active).

## Ordering

- Order activity by `seq` within one `epoch` and `clockId`. The session `revision` is independent.
- Live and replayed events apply only on the connection that set the baseline, with the same epoch
  and clock and a higher `seq`. A duplicate or older `seq` changes nothing, deadline included. Gaps
  are harmless because every snapshot replaces the whole state.
- Another epoch or clock, or no baseline, means `needsSnapshot`: fetch a fresh first page
  (throttled), do not apply the event.
- A baseline answer from another connection or an older request is dropped. A same-epoch answer at
  or below the current `seq` does not roll back newer events.
- After a reconnect nothing animates until the new connection's baseline arrives.

## Receiver policy (approved by Robin)

Conservative for every actor, the session and each subagent instance:

- A new actor epoch, or a new clock, is authoritative only from a fresh first snapshot page (or a
  session list for the session row) answered on the current connection. Until then the actor is
  not live.
- Agent actor identity is exact: `(parentSessionId, spawnToolCallId, instanceId)`, never the
  display name. Its `executionActivity` is fenced per that identity by `epoch` and `seq`. The current
  host re-clocks it to its own `clockId`, but the contract validates it on its own clock: if its
  `clockId` does not match the connection's clock baseline, it is never live (static or unknown).
- This policy is Android's choice, not a host guarantee.
- A finished or queued agent is never shown as active. An agent row that becomes `unknown` stays
  unknown; it is never shown as done.

## Lifecycle and input isolation

- Waiting for input and slot queueing are not activity phases.
- Session: input waiting stays `Session.status = waiting` and open questions.
- Agent: `needsInput: true` appears only with `state = running` and means this agent itself waits.
  It is never inherited by the parent or siblings. Absence does not prove there is no wait. Queueing
  is `state = queued`.
- `idle` is an explicit sequenced clear; `unknown` (new owner before its first proof, lost bridge,
  offline) never means done.

## Thinking level and colors

- The wire carries the typed `thinkingLevel` (`off`, `minimal`, `low`, `medium`, `high`, `xhigh`,
  `max`), never a color. A missing level means unknown, not `off`.
- Requirement: a missing `thinkingLevel` stays a neutral unknown thinking color. It is not mapped
  to `medium`.
- `off` and `minimal` may render as `medium`, following the Atlas rule (their colors are near-grey).
- Thinking colors follow the theme selected in the app:
  - Synthwave (currently selected): the verified Synthwave 84 palette below, from the host repo
    (`.pi/themes/synthwave-84*.json`).
  - Brand and Dynamic themes: an appropriate Material color mapping per level, defined with those
    themes.

| Level | Dark | Light |
|---|---|---|
| low | #9d8bca | #9479af |
| medium | #b893ce | #694080 |
| high | #ff7edb | #a01870 |
| xhigh | #f97e72 | #a13232 |
| max | #fede5d | #795500 |

- Other phases: pending dim, tool in accent. Writing is cyan, an explicit user requirement (the
  Synthwave tool-title color).

## Unchanged

Existing glyphs, the tasks UI and the compaction display in the Android UI stay as they are (the
host producer separately resets the phase to `pending` on compaction). Structured activity only
feeds the existing glyph language; it does not replace these features. Legacy hosts keep today's
behavior.

## Validation

- [x] Initial implementation validation: `scripts/quick-check.sh`: resource parity, all
      app/androidTest/benchmark/Wear compilations,
      4,074 app unit tests and 67 Wear unit tests; no failures, errors or skipped tests.
      Log: `build/structured-activity-final-check-fixed.log` (148 seconds; historical run, not a
      current-head count).
- [x] Shared host fixture and ordering/freshness vectors, legacy host fixture and ungranted
      connection behavior are covered by the app suite.
- [x] Real fullscreen recomposition regression: identical ledger instance, all four lifecycle/input
      gates, visible tool card, and live glyph replaced by a static glyph.
- [x] Actor-source regression: 29 repository tests pass. Temporarily bypassing the actor's source
      watermark makes the terminal-row regression fail; production source was restored
      byte-identically and all 29 tests passed again.
- [x] Independent Opus reviews of the implementation and both final test corrections.
- [ ] CI on the exact final PR head: latest CI results are tracked on the PR; all six required
      checks (unit/build, lint, benchmark isolation, phone emulator, minified smoke, tablet
      emulator) must pass on that exact head. Earlier-head results do not count.

The three chat Settings instrumentation selectors use the existing main-branch fix from
`2f2d871a105b13e55d8bc74cb358b3f7c95438eb`, transplanted only into
`RemoteNavigationUiTest.kt`. Non-chat Settings navigation is unchanged.

### Known pre-existing retry limitation

An invalid fake-host sequence exposed a legacy Timeline gap-resume retry: a host repeatedly
answering below a journal revision it already emitted can cause immediate repeated snapshot
requests. The same retry exists on current main; structured activity recovery is separately
bounded and throttled. The source-ordering test now uses contiguous journal revisions, holds the
intended recovery request and caps fake-transport requests so regressions fail instead of exhausting
memory. Production Timeline retry hardening is a separate proposed follow-up, not implemented here.

### Main-branch integration

The branch integrates `main` at `ab90f7891f805ccf64faa48850725163292dafec`. That merge preserves
main's phone diagnostics, session titles, questionnaire answer cards with jump-to-question, and
the frosted popup/shadow styling, and keeps main's separate Braille compaction indicator
alongside typed activity. Validation and review results for the integration are tracked on the PR.

CI checks out the PR head explicitly, not a synthetic merge. When `main` advances beyond the
integrated revision, a fresh integration with its own check/review pass is needed before readiness
or merge.

## Real device checks (pending, Robin)

- [ ] Reconnect: activity stops animating, opt-in is sent again, resumes after the new baseline
- [ ] New clock: daemon restart, no stale animation, fresh baseline applied
- [ ] New epoch: session re-registration and subagent resume or queued start
- [ ] Tool running longer than 30 s keeps animating through renewals
- [ ] Delayed data and background: a late frame does not animate again; state after resume
      from background is correct
- [ ] Samsung: accessibility services and reduced motion or removed animations
- [ ] Wear OS surface, if it shows activity
