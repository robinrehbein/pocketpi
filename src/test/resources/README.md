# Test fixtures

The `*-v1.json` files here are copies of the Remote protocol fixtures in the noah monorepo, under
`packages/pi-atlas/extensions/remote/protocol/fixtures/`. Copy them byte for byte; change them there
first.

`session-worker-controls-v1.json` holds the wire shapes of `session.host_worker.v2`: a worker
`sessions.create` result, a terminal session, a `follow_up.status` event, a `session.close`
`accepted` result, a `reloaded` result and the `unsupported` refusal of a pi without the local
capability.
