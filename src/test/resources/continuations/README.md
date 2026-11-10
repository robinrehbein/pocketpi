# Session continuation fixtures

`session-continuations-v1.json` is a byte-identical copy of
`packages/pi-atlas/extensions/remote/protocol/fixtures/session-continuations-v1.json`
from `noah-financial-services-gmbh/noah-monorepo` commit
`74f995e2566103e9b77772b98ef4b0a87e989112`.

The contract uses `session.continuations.v1`, advertised only through the
`capabilities.v3:` projects route. Snapshots opt in with `includeContinuations: true`.
Lifecycle changes use the existing `snapshot.required` barrier; cancel acknowledgements
are followed by a fresh authoritative snapshot.

The host change must land before the Android change. A copied fixture and local source
commit do not imply that a deployed host or an already-running session supports this
contract. Both the loop and Remote extensions must be updated in the session owner.
One-shot wakeups and loops are volatile; an extension/runtime restart loses them.
Goals retain the scheduler's existing restore behavior. Updating the host daemon alone
does not reload the session's extensions.
