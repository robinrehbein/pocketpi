# Browser context retention implementation plan

**Goal:** Preserve a recovery-enabled device's browser instance and tabs across its last connection loss while immediately revoking input ownership.

**Architecture:** Retain only a project/session/device-authorized context with a host-issued guard independent of the old socket. Retained instances still count toward the existing 16-browser limit; capacity returns busy rather than evicting someone else's work. No timeout silently discards live session context. New connections pass normal authorization and explicitly acquire a new lease. Legacy cleanup remains unchanged.

**Spec:** `docs/remote-development-goal.md`, `docs/testing/browser-reconnect.md`.

## Tasks

- [x] Reproduce last-device browser identity loss with a failing BrowserService regression.
- [x] Implement connection-independent retention authority, immediate revoke/reset, revalidation on session/project/device/grant change, and fail-closed cleanup.
- [x] Add long-idle, reset ordering/failure, stale lease and retained-capacity regressions.
- [x] Verify focused and full host checks, including actual daemon authority behavior.
- [x] Prove last-participant socket recovery through encrypted Android transport and actual Chromium; inspect phone/tablet output.
- [x] Independently review, commit locally, remove owned test credentials/processes and record qualification boundaries.

## Constraints

A guard captured from the dead peer cannot authorize retention. The new connection cannot reuse an old lease, receive ownership from retention, skip project/session trust or cause automatic input replay. Shutdown, disabled feature, lost project/session and revoked device/grant close the retained instance. Native browser/process death and an authorized session's end remain explicit context-loss boundaries.

## Parallel gates

Linux packaging is prepared separately in scratch; the preview source inventory confirms that a protected HTTP/WS renderer is not implemented yet. Neither item is completed by this retention increment. The full goal remains active.
