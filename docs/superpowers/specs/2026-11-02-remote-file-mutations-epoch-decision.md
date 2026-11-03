# Remote file mutations: lock domain and continuity decision

Decision needed before Task 1 of `docs/superpowers/plans/2026-11-02-remote-file-mutations.md`.
The design (`2026-11-02-remote-file-mutations-design.md`, lines 39–47) requires one exclusive
lock domain for every path that opens the mutation store, and a trusted way to detect that the
Host lost or rolled back its operation journal together with its provisioning marker. Without
it, an old request ID could look new after such a loss and run twice, so the capability stays
off.

## Lock domain

Proposal: the Host takes an exclusive `flock` on `mutation-store.lock` in `PI_REMOTE_STATE_DIR`
inside `HostDaemon.start`, before the store opens, and keeps the descriptor for the daemon's
lifetime. The store's constructor takes the held lock handle, so no code path (CLI, service,
devcontainer supervisor, tests, e2e fixtures) can open a store without it. A second Host on the
same state directory fails to start mutations; reads and saves are unaffected. Losing the lock
(state directory replaced underneath) disables mutations until restart.

This needs no decision beyond agreeing that mutations are tied to the daemon, not usable from a
standalone CLI process.

## Continuity: the open choice

### Option 1 — device-anchored epoch (recommended)

On first use the Host creates a random journal epoch and stores it with the journal. The
capability response carries the epoch; the Android durable journal stores it with every pending
operation, and every mutation and outcome query sends it. The Host refuses an operation whose
epoch is not its current one and answers outcome queries for it with `unknown` (never executes
it). After the Host loses or rolls back its journal it has a new epoch, so the phone's pending
operations from the old namespace can never run again; the phone shows them as unknown and
offers recovery, as the design already requires for lost receipts.

- Works the same on Mac, Linux and devcontainers; no OS secret store needed.
- The anchor is every paired device's journal. A device that never saw the old epoch has no
  pending operations from it, so nothing can be replayed.
- Cost: one field in the capability response, the mutation commands and the Android journal;
  a protocol change in noah first.

### Option 2 — OS-anchored counter

Keep a monotonic counter (or the epoch's hash) in the macOS Keychain or a Linux secret service
next to the existing Host secrets, and refuse to start mutations when the state directory's
journal is older than the anchor.

- Detects loss on the Host itself, without a device.
- Not available in devcontainers or headless Linux without a secret service; those Hosts would
  have to keep mutations off. Keychain access prompts on Mac are another failure mode.

### Option 3 — keep mutations off

Ship nothing until a better anchor exists. Reads and saves keep working.

## Recommendation

Option 1, optionally with Option 2 added later on Mac as a second check. It matches the design's
"reject identities from the lost namespace" rule and covers every Host the goal names.
