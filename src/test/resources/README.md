# Test fixtures

The `*-v1.json` files and `paged-editor-wire.json` here are copies of the Remote protocol fixtures in the noah monorepo, under
`packages/pi-atlas/extensions/remote/protocol/fixtures/`. Copy them byte for byte; change them there
first. The copies match noah `dev` commit `94fb402e3571712cb8430f57bcddec14f78f18fe` (browser history state).

`session-close-terminal-v1.json` holds the wire shapes of `session.close.terminal.v1`: closing an
idle terminal session, its `accepted` result and the refusals (`busy` with and without unsent Mac
editor text, `unsupported`, `offline`, `internal` "Close result unknown; refresh").

`session-worker-controls-v1.json` holds the wire shapes of `session.host_worker.v2`: a worker
`sessions.create` result, a terminal session, a `follow_up.status` event, a `session.close`
`accepted` result, a `reloaded` result and the `unsupported` refusal of a pi without the local
capability.

`push-run-failure-v1.json` holds the `push.run_failure.v1` push data: the FCM data a failed run's
`complete` push carries, and a category a later Mac may add.

`files-mutations-v1.json` holds the wire shapes of `session.files.create.v1`, `.rename.v1`,
`.delete.v1` and `session.files.mutation-recovery.v1`.

`session-activity-v1.json` holds the wire shapes of `session_activity.v1`: opt-in strings,
`session.activity` events, the `activity` and `activityClock` fields of `sessions`, `snapshot`,
`pong` and `session.events` results, and subagent `executionActivity`.

`insights-v1.json` holds the `compactionListValid` shapes (the `sessions.list` compaction opt-in and
its result) from noah TOOLING-1254, now on `dev`.

`subagent-metadata-v1.json` holds the wire shapes of `subagent_metadata.v1` (noah TOOLING-1264): the
opt-in strings granted and refused, valid and invalid card `metadata`, and the projection without it
for a peer that did not opt in. It is copied from noah-monorepo PR #85, branch
`task/tooling-1264-subagent-metadata`, commit `951ca2c129601e7dda734aa4bebfd9f691590fcd`, not yet
on `dev`; `session-activity-v1.json` is unchanged there.

`host-stats-v1.json` holds the wire shapes of `host.stats.v1`: the request, a Mac sample, a first
sample without CPU percent, a Linux container with and without limits, and refused shapes.

`session-handover-v1.json` holds the wire shapes of `session.handover.v1`: the request, its
`accepted` result and refusals (`busy`, both `unsupported` sentences, `offline`), every
`session.handover` event state and error, the old chat's snapshot `handover` field, the new chat's
`handedOverFrom` field and its summary message, and refused shapes.

`session-list-context-v1.json` holds the wire shapes of `session.list.context.v1`: `sessions.list`
requests with `includeContext` alone and together with `includeCompaction`, list rows with a fill,
after a compaction (unknown tokens and percent), over their window and without a fill, and refused
shapes (unknown or missing keys, bad counts and percents, a `null` context, a `false` or misplaced
flag, a fill outside a list).

`capabilities-v4.json` pins the `capabilities.v4:` route: its request IDs, the request IDs that are
not v4, every route's list bound (v4 128, v3 64, v2 and v1 32) and the six v4-only capabilities.

`browser-history-v1.json` holds the wire shapes of `session.browser.history.v1`: the `back`,
`forward`, `reload` and `stop` commands, the `browser.tabs` result, the `not_found` and `forbidden`
refusals, and invalid commands.

`browser-history-state-v1.json` holds the wire shapes of `session.browser.history.state.v1`: the
`browser.history.state` event with each combination of `canGoBack` and `canGoForward`, and invalid
events (a missing, non-boolean or null flag, a missing or blank tab, a missing incarnation, extra
fields, and the flags on `browser.document`).

`emulator-controls-v1.json` holds the wire shapes of `session.emulator.controls.v1`: the new keys,
`text`, `long_press` and raw multi-touch inputs, `emulator.rotate`, the `emulator.rotated` result,
invalid ones, and `needsKeyboard`: texts that parse but need `session.emulator.keyboard.v1`.

`emulator-keyboard-v1.json` holds the wire shapes of `session.emulator.keyboard.v1` (noah
TOOLING-1458): Unicode text, the editing keys and modifier chords that need the capability
(`commands.valid`), ASCII text and plain keys that parse the same way but keep the ADB path
(`commands.adb`), and shapes that never parse (`commands.invalid`).

`session-limits-v1.json` holds the wire shapes of `session.limits.v1`: the route rule, `projects`
results with and without `limits`, and invalid ones (`limits` on another route, unknown or missing
keys, bounds out of order or out of range).
