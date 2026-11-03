# Shared Pi and Android browser qualification

## Contract and execution

Use the actual Pi 1.4.0 CLI, its public remote extension and a local offline faux provider,
with a temporary project, home, settings, sessions, browser profile and relay. The host must
bind its registered session's owner PID to the actual Pi process. No user login/configuration
or paid model provider is used.

The opt-in Android method is
`BrowserTransportEndToEndTest#actualPiAndAndroidShareLoginAcrossExplicitHandoffs`.
The optional host fixture arguments are `--node` and `--pi-cli` in addition to `--chromium`
and `--output`. Copy its private JSON and public CA to the dedicated emulator as described in
[browser-transport-e2e.md](browser-transport-e2e.md). The argument remains `browserFixturePath`.

1. Pi opens, acquires and creates the login tab through actual `remote_browser` calls.
2. Android opens that same browser and tab as a reader. Pi explicitly hands control to its
   connected device participant; Android refreshes and explicitly obtains its current lease.
3. Android sends the fixture's unique login value once, waits for accepted input and submits
   once. Only an exact value sets an HttpOnly cookie and redirects the same tab.
4. Android uses the pane's handoff menu to return control to Pi. Pi opens/acquires the same
   browser and navigates the original tab to the protected endpoint through `remote_browser`.
5. The endpoint must see the cookie exactly once; Pi verifies unchanged browser/tab IDs.

Stage triggers are fixture-only HTTP requests; they start actual Pi RPC turns, not direct host
browser-control calls. Out-of-order/repeated triggers are rejected. Public status contains
proof flags and identifiers, never leases or cookie values. The output JSON contains temporary
pairing credentials and must stay private. Stop the fixture and remove emulator copies after use.

## Current evidence

The initial combined phone run reached a successful Pi-to-device handoff, then failed because
Android subscribed to an unconfigured viewport before its first resize. The image failure
released the acquired lease. A failing unit regression reproduced the initial revision-zero
watch. After deferring that watch until a successful viewport configuration, the combined
phone flow passed in 12.695 s with verified actual Pi ownership, both handoffs, exact login,
same browser/tab, one protected request carrying its cookie, and zero tool errors.

A subsequent review found that a reader initially at revision zero could not discover a later
agent viewport through Refresh. The client now fetches authoritative Open metadata without resetting its session or lease. An owner's newer document revision must
still come from its sequence-bearing document event or its own viewport acknowledgment;
metadata alone must not preempt that event. Both cases have failing-before/passing-after
regressions, including late Refresh results after a document event. The required final
quick-check passed 3,053 app and 34 Wear unit tests, all configured source-set compilation
and resource parity in 113 seconds. Host extension checking and root formatting passed;
the independently reviewed host fixture is committed locally as `d50101c02`.

The final-source combined flows passed on the phone emulator (1080×2400, density 420) in
8.315 seconds and the tablet emulator (2560×1600, density 320) in 10.898 seconds. Both use
actual Pi 1.4.0 and the final app APKs; the complete stage ends with `verified`, exact login,
one cookie-bearing protected request and zero tool errors. Screenshots of the signed-in page
were inspected on both sizes. The retained-control warning now asks the owner to check the
page, while the lost-control message still requires reacquisition. No production host or
user pairing configuration was activated.

The earlier standalone transport and standalone Pi probes remain separate evidence. This
controlled local login does not qualify real websites, neural-model decisions, production relay,
physical Samsung devices, public-network reconnect or independent Linux/devcontainer hosts.
