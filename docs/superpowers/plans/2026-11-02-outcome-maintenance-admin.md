# Explicit local-admin outcome maintenance

Root Git-first clean/up-to-date App0e986a91 Hosta54cdc056. Existing local Unix
socket IPC is the same-owner administration surface used for pairing/revocation.
`/remote` is wrapped in withLocalDialog; destructive maintenance additionally
requires interactive TUI/hasUI, like stop. No encrypted remote Payload command,
capability, automatic scheduler, personal ledger operation or public gate change.

Decision: `/remote outcomes` shows aggregate preview and asks local confirmation
before explicit maintenance. Explain permanent positive-evidence loss, missing
still unknown, Android draft/fence preserved and minimum observed retention.
No eligible records means an explicit maintenance can save retention progress
without deleting records; do not silently write during preview. Cancel does not
commit. Noninteractive mode may obtain read-only preview but must not commit.

Local IPC `outcomes.maintenance.preview` returns aggregate summary and random
single-use confirmation token; `outcomes.maintenance.commit` requires that token on
the identical LocalPeer. Token lives at most 60 seconds of monotonic process time,
invalid on restart/disconnect/replacement, one per peer, bounded total storage.
No tuple/device/text disclosure. Reject invalid fields rather than stringify them.

Bind commit to the exact eligible full tuple/state set observed at preview using
an internal deterministic fingerprint. Ledger serializes recheck+delete and skips
active pins. If eligibility changes, fail before write and require new preview;
no unseen entries may be deleted. `previewMaintenance()` stays file-read-only;
add a separate prepared view/fingerprint API or optional expected fingerprint on
expireRetained while preserving existing internal tests/API. Never trust a client
supplied fingerprint or extend token TTL at commit. Existing exact applied release
and reserve/ACK ordering unchanged. No preview/confirmation lock across user time.

- [x] Inspect existing local IPC administration and withLocalDialog/TUI guards.
- [x] Independent tests + root RED for peer-bound one-use tokens, TTL/stale
  snapshots, cancel/noninteractive UI, no remote parser exposure and ledger race.
- [x] Production service/ledger snapshot binding + local IPC and local-only command.
- [x] Root focused checks/types/review and isolated actual local IPC proof of
  preview, commit, capacity relief and expired lookup unknown.
- [ ] Follow-up: retain Android fence after expiry of a genuine Source receipt
  through the actual private fixture before calling resource gate closed.
- [x] Precise docs and local commits after verification. No live host mutation.
