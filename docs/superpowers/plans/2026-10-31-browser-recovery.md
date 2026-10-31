# Browser document and moving-view recovery

This increment implements the already approved shared-browser goal: ordinary navigation must
retain the validated owner while fencing stale pixels and input. No live configuration/grants.
Host base 73bf8bd91, app base c1189ca; root updated both upstreams before work. Host work is
isolated on codex/browser-document-recovery. Existing full-goal qualification gates remain open.

## Root causes reproduced

Before this increment, the navigation regression rejected the old capture guard but accepted
old-viewport input after a document boundary. The moving-scroll regression threw Changed visual
viewport once two captures differed. The native pane could display a cached image after both
image and control were lost. These are distinct from the previously fixed CDP screenshot/resize race.

## Contract

- Add v3 capability `session.browser.recovery.v1`, only alongside the authorized browser
  capability. `browser.open` and `browser.watch` accept optional boolean `recovery`; clients
  set it only after negotiating this capability. Legacy behavior remains fail-closed.
- A main-frame document boundary for the current view or viewport tab synchronously advances
  the owner's viewport revision and invalidates the view, without granting a different owner
  or resetting/reusing sequence numbers. Subframe and same-document navigation do not advance
  input authority; they still invalidate capture as appropriate. Unrelated tabs do not rotate
  the current view's revision.
- One coalesced record worker drains already admitted operations and resets held input.
  Gate new mutation/sequence admission until its authoritative reset snapshot is delivered;
  never await this barrier from inside the operation currently occupying the owner queue.
  Recheck session/project/participant/lease immediately before dispatch and delivery.
- Owner-only direct encrypted event `browser.document`: sessionId, browserId, incarnation,
  tabId, generation, fromViewportRevision, viewportRevision, bounded tabs, optional public
  owner, and optional control {leaseId,nextSequence}. Control is sent only to the exact
  authenticated current owner connection after successful reset. Viewers receive no control.
  The covered revision range can coalesce boundaries; nextSequence includes every reservation
  consumed before the barrier. Same known lease/generation proves continuity; no guessing.
- Keep the input admission gate until the owner event's actual delivery callback. If the
  owner is legacy, release the gate after reset without an automatic continuity event;
  stale input still fails its viewport check. Revocation, handoff, disconnect, reset failure
  and a replaced connection cannot publish old credentials or resurrect ownership.
- Android accepts only current connection/session/browser/incarnation/tab and a strictly
  newer revision covering its current revision. It fences old async replies/frames, discards
  unsent input, preserves uncertain drafts, adopts host nextSequence only for the same
  already held lease/generation, and re-watches without takeover/replay. Missing/conflicting
  continuity fails closed to read-only. Keep unresolved mutation reservations until complete.
- Add `browser.frame.waiting` with browserId,streamId,tabId,generation,viewportRevision and
  reason `moving`. Only recovery-enabled watchers get bounded-rate waiting heartbeats while
  consistent capture is temporarily unavailable. The sender retries later, never sends a
  mismatched snapshot, never loops tightly, and fatal/auth/ACK errors remain terminal.
  Android validates exact watch scope before extending its inactivity timer; stale waiting
  events cannot keep a replacement watch alive. New positional input waits for a valid frame;
  wheel continuation and reserved releases must remain possible while moving.
- Separate frame freshness guards from read authorization after sealing. Old scope-tagged
  ciphertext must be delivered (rather than dropped creating a crypto sequence gap) if only
  the document/view changed and read authority still holds; server rejects old-viewport
  input and clients fence old frame scopes. Authorization loss after seal still closes only
  the captured peer. Never weaken exact-peer/project/grant checks or JPEG bounds.

Viewport revision fences browser input. Existing non-input commands such as navigate, close,
new-tab and viewport remain lease/generation-scoped intents without a document-revision field;
the barrier drains admitted operations and rechecks authority rather than changing those wire
commands. This does not provide document-relative fencing for every non-input intent.

## Verification

The Android quick-check passed 3,044 app and 34 Wear unit tests; the host Remote suite passed
3,018 tests with 23 skipped. The encrypted Android fixture passed the full real Chromium link,
blue JPEG/Compose, preserved unsent draft, same-lease/no-acquire and 480-pixel wheel flow on
phone and tablet emulators in 11.455 s and 11.502 s respectively. Those runs preceded the
final host control-flow refactor. Final-source repeats passed on tablet (10.893 s) and phone
(12.454 s), with the IME hidden before the single text send. The phone test also corrected a
cross-run telemetry baseline without relaxing the fresh-frame/480-pixel requirement. A prior
phone attempt emitted moving heartbeats; deterministic tests establish their scope/deadline
rules. A later increment adds explicit feedback when a text send is locally refused during a
viewport transition, preserving the draft without retry. It also corrects the retained-control
uncertainty message. Physical IME transition qualification remains open.

Physical Galaxy devices, process-death recovery, independent Linux/devcontainer, public-network
and full real-model browser-turn qualification remain untested by these fixtures. The Pi
CLI/model/tool probe is separate evidence and must retain its own scope.
