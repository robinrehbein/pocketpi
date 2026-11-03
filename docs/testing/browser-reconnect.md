# Browser reconnect qualification

## Implemented behavior

After a transient transport loss, an already open browser stays attached to its exact
route/project/session. Successful session restoration and browser capability discovery rebind
the retained view to the new connection as a reader. The selected tab, unsent Compose draft
and uncertain-input warning survive. The client never reuses a lease or automatically sends
or acquires control. An interrupted initial Open can also resume without a null pane or an
old reply overwriting the new view.

The repository still performs its original request and selection-epoch cancellation. Only the
disconnected browser pane is preserved for the same RESTORE selection. Healthy panes, public
closure, navigation and capability loss do not trigger automatic reopening. Actual context
changes, including fallback to a parent, close the retained old pane.

## Actual interrupted-input test

The existing shared Pi/Android fixture has an optional `--reconnect` mode. The actual Pi 1.4.0
CLI uses its public remote extension and an offline local provider. It creates a decoy tab and
then a separate login tab. Android selects the login tab, acquires by explicit handoff, and
performs the exact local test login.

The test arms a single input cut, applies one unique value through actual Chromium input and
holds its reply outside the browser owner's operation queue. Android edits its local draft to
a different unsent value. The fixture destroys both ends of the actual Android TLS/WebSocket
upgrade and rejects new upgrades temporarily; status HTTP, Pi IPC and Chromium stay alive.
Actual Pi Open/Participants commands must prove that the old owner and old device participant
are gone before the gate permits reconnect.

Android reconnects using its normal repository timer and restores the same browser/tab as a
reader. Actual Pi observes a distinct new participant with no owner; host input/acquire counters
must not advance. The page value was applied exactly once, the newer local draft remains, and
the uncertain outcome is visible. Only then does the test explicitly acquire, hand back to Pi,
and verify the original tab's login cookie through exactly one protected request.

## Running and evidence

Start the host fixture as in [shared-browser-handoff.md](shared-browser-handoff.md), adding
`--reconnect`. Pass `browserReconnectExpected=true` with `browserFixturePath` when running
`BrowserTransportEndToEndTest#actualPiAndAndroidShareLoginAcrossExplicitHandoffs`.
The expected-mode argument makes a missing/wrong fixture fail rather than silently skipping
the reconnect branch. Temporary credentials and the fixture CA need the same handling and
cleanup as the shared-browser test.

The phone emulator flow passed in 14.175 seconds. It proves actual socket closure, old ownership
cleanup, a different authenticated participant, unchanged automatic input/acquire counters,
retained draft and same-tab login continuity. The restored reader screenshot was inspected.
The tablet flow passed in 11.215 seconds. Its restored reader screenshot was also inspected.
The final required quick-check passed 3,058 app and 34 Wear unit tests, all configured source
sets and resource parity in 80 seconds. The 464 focused BrowserClient/RemoteRepository tests
and debug/instrumentation APK assembly passed before the real runs. Host extension checking
and repository formatting passed; its independently reviewed fixture is committed locally as
`08bc847e7`. Existing fixture modes retain their prior protocol; no wire field was added.

The first run reached applied input and old ownership removal but did not reconnect because
the standalone test activity had not supplied the repository's foreground/network availability
signals. The corrected test supplies foreground and a stable fixture-loopback network signal;
HTTP control really remains reachable throughout the WebSocket cut. This qualifies the actual
transport and repository recovery, not MainActivity lifecycle or Android's real network monitor.

## Remaining boundaries

Pi remains attached as a browser participant during this test, keeping the browser instance
alive. Last-participant disconnect, host/browser process death, app process death, real device
switching, physical Samsung, public-network/radio changes and independent remote Linux remain
separate gates. No production host configuration, real website login or paid model request is
part of this fixture. The overall development-environment goal remains active.
