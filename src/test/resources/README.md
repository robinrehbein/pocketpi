# Test fixtures

The `*-v1.json` files and `paged-editor-wire.json` here are copies of the Remote protocol fixtures in the noah monorepo, under
`packages/pi-atlas/extensions/remote/protocol/fixtures/`. Copy them byte for byte; change them there
first. The copies match noah commit `5d926956fabe665dad4e930d0a43c5900b5f877c` (branch
`codex/pocketpi-run-failed-push`, noah-monorepo PR #60, on top of `dev`).

`session-worker-controls-v1.json` holds the wire shapes of `session.host_worker.v2`: a worker
`sessions.create` result, a terminal session, a `follow_up.status` event, a `session.close`
`accepted` result, a `reloaded` result and the `unsupported` refusal of a pi without the local
capability.

`push-run-failure-v1.json` holds the `push.run_failure.v1` push data: the FCM data a failed run's
`complete` push carries, and a category a later Mac may add.

`files-mutations-v1.json` holds the wire shapes of `session.files.create.v1`, `.rename.v1`,
`.delete.v1` and `session.files.mutation-recovery.v1`.
