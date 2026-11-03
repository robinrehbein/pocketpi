# Browser context retention qualification

## Local qualification, 2026-10-31

A recovery-enabled device can now disconnect as the last browser participant without closing its
browser or tabs. Retention lasts for the authorized project/session/device lifetime without a TTL.
Retained contexts count toward the 16-browser limit; capacity returns busy rather than silently
evicting work. Disconnect immediately revokes ownership and resets held input. Reopening waits for
pending cleanup, passes normal authorization and returns read-only. It never replays input or grants
a new lease automatically. The daemon supplies a retention guard independent of the dead socket;
project/session/device/grant invalidation and shutdown still close the context.

Independent review found and prompted regression tests for two lifecycle races: an owner departing
before the last reader could leave a pending reset untracked; an in-flight document delivery could
close a retained context through the old participant's guard. Shared cleanup barriers and retrying
departed-participant document work address those cases. Actual reset or current-participant delivery
failures remain fail-closed. Both original regressions were independently observed failing before
correction. Final review round 3/3 found no further blocker.

## Actual Android transport

The device-only fixture starts a real Pi 1.4.0 session without a browser participant. Android creates
a decoy tab and a non-first login tab, logs into a controlled local page and is verified as the sole
browser participant. The fixture applies one input and withholds its result, then cuts both real
TLS/WebSocket legs. Only after the resulting zero-participant gap does Pi join and prove that the
original browser/tab survived and the old owner/peer disappeared. The app restores the same selected
tab as a reader, preserves a different newer unsent draft and shows an uncertain input outcome.
Automatic acquisition and input counts remain unchanged. Explicit acquisition and a UI handoff
then let Pi verify the same tab's login cookie exactly once.

Final phone 1080×2400/density420 and tablet 2560×1600/density320 runs passed in **23.315 s** and
**10.325 s**. Logs are `/private/tmp/browser-device-only-phone-diagnostic.log` and
`/private/tmp/browser-device-only-tablet.log`; sanitized `*-proof.json` files record the outcome.
Both saved screenshots were visually inspected. A prior run failed at its final fixture ID assertion
because the device-only fixture had not published the independently observed IDs; this was corrected.
Another run timed out before the pending text reached the host. Additional test-only diagnostics
were added without retrying input; the final phone/tablet runs passed, but the sporadic pre-dispatch
loss has not been reproduced or fixed and remains an open input-delivery qualification item.

The fixture uses real Chromium and encrypted transport with a deterministic offline provider.
The standalone activity supplies foreground/network availability signals. This does not qualify a
neural provider, public website, real radio/network transition, physical Galaxy, process death or
switching devices. Host/browser process death and session termination are explicit context-loss
boundaries. The full remote-development goal remains active.

## Checks and source

The final host suite passed **3,023 tests with 23 skipped**; six related tests passed separately
before the final lifecycle correction. BrowserService contributes 46 passing tests, including the
reset and document races. Host extension checking passed with 92 warnings and three infos.
The diagnostic APKs compiled and both end-to-end runs used them. Android quick-check passed with
unit tasks up to date, so this increment makes no fresh unit-execution claim. App production code
is unchanged by this increment. Local Linux passed 26 runtime/worker tests independently; its
sandbox and packaging limits are recorded in [Linux qualification](linux-host-qualification.md).

Host implementation is local commit `dcb9c7931` (base `08bc847e7`) on
`codex/browser-context-retention`; app base is `8983dc0` on
`codex/remote-environment`. See the [implementation plan](../superpowers/plans/2026-10-31-browser-context-retention.md).
