# Explicit local-admin outcome maintenance

`/remote outcomes` now uses the existing same-owner local Unix IPC administration
surface. It previews aggregate counts and asks an interactive TUI/hasUI confirmation
under the existing withLocalDialog boundary. Noninteractive contexts receive only
the preview; they cannot commit through this command. No encrypted remote Payload
operation, capability, scheduler or public outcome gate is added.

The confirmation explains minimum observed retention, permanent loss of positive
evidence, subsequent unknown outcomes and preserved Android drafts/unresolved sends.
When no entries are eligible, an explicit confirmation may still checkpoint observed
retention progress without removing any records. Cancel performs no commit. The
captured connection is rechecked after asynchronous operations; there is no automatic
retry of uncertain maintenance. The ledger's existing lazy initial provisioning may
create a fresh empty store on first access; preview does not rewrite an existing
ledger or checkpoint its age.

The Host issues a random, single-use token bound to that exact LocalPeer and the
complete eligible tuple/state snapshot. Tokens expire after at most 60 monotonic
seconds; disconnect, restart, replacement preview, clock uncertainty or changed
eligibility invalidates confirmation. Token/in-flight storage is bounded. The
ledger checks the fingerprint under its queue and checks the token/connection again
immediately before rename. Failure leaves the old ledger intact. No tuple, device,
editor text or secret is returned in the preview. Local IPC validates exact fields;
the remote protocol parser rejects these command types.

## Recorded validation

Final checks pass: 91 tests across nine maintenance/UI, ledger/retention/crash and
positive-delivery integration files; extension TypeScript; scoped formatting/diff.
Tests first fail because the new modules are absent. Two additional behavioral
RED tests expose token expiry/disconnect while a commit waits behind another ledger
write (2 failed, 15 passed); the final pre-write guard fixes both. One TypeScript
pass initially fails on disallowed parameter-property syntax and a missing test
array annotation; actual formatted source is corrected and the final check passes.

Three integration cases use real temporary Host Unix sockets and the real ledger.
They prepare scoped metadata and inject a monotonic retention clock; they do not
claim those prepared entries came from real Source SDK writes. With 32 entries,
one active reservation remains protected while confirmation removes 31 eligible
entries. Capacity becomes usable again; after the active callback finishes, the
remaining eligible entry may be removed. Young replacement data remains. Another
connection and reused/invalid tokens are rejected, as are extra IPC fields and
remote Payload command types. The command helper is exercised against real IPC
with simulated confirm/cancel callbacks: cancel retains an applied outcome;
confirmation removes it and the exact lookup becomes unknown.

These are actual socket/ledger operations with injected time and prepared data.
They are not rendered Pi-TUI interaction, thirty calendar days of operation, or an
Android-after-expiry qualification. Scoped review explicitly preserves those
boundaries. No personal or live Host ledger was used. The next resource proof must
perform maintenance on a genuine Source-written record and verify Android keeps
its exact unknown attempt, text and no-replay fence; public enablement remains open.
