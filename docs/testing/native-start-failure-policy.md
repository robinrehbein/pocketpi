# Native start failure policy qualification

Native Host workers remain the regular runtime. RPC is frozen compatibility for
an unavailable Native backend and explicit legacy routes, not a second product
path. A failed attempted Native start now reports failure instead of silently
starting RPC.

## Behaviour and ownership

`Sessions.createNative` preserves the existing backend-unavailable compatibility
result. When a Native launch is attempted, launch exceptions propagate for new,
reopened and forked sessions. A worker that exits before registration or times
out is discarded and the request receives the existing bounded `offline` error.
No new wire error category or Android translation was added; Android's existing
failed-create state displays the failure generically.

Cleanup waits for actual worker termination before removing a newly copied
reopen/fork file or closing its snapshot. Held, registered or terminal-used
workers retain their resources. Snapshot closure remains idempotent; a successful
discard is not repeated merely because the initiating request now throws. A
false discard does not establish ownership of cleanup and does not close that
snapshot. The original saved session is preserved. Shutdown, permission refusal,
late registration, SIGHUP/SIGKILL waits and existing no-backend compatibility
retain their scoped regression coverage.

## Evidence

App baseline `98a58989`, Host baseline `1c36a98d8`; both were clean and updated by
fast-forward pull before work. Production changes are limited to Host Sessions.
The App has documentation changes only, so no new Android build/device claim is
made in this increment.

- RED: six added Native startup regressions produced five expected failures and
  one pre-existing passing launch-exception case. Failures demonstrated missing
  rejection while the old implementation started RPC.
- GREEN: all six focused regressions passed.
- Affected lifecycle suites: 132 passed, 16 explicitly skipped (the real-Pi opt-in
  was absent in that run), across ten passed and two skipped files. The initial
  broad run had one stale success expectation; it was corrected while preserving
  its no-snapshot-close assertion, then the full affected set passed.
- Explicit real-Pi run: five selected cases passed on local Pi 1.4.0, Node 24.18.0
  and node-pty, using isolated configuration and a local fake model provider.
  They cover worker registration and shell, reopen on a copy, retry fork, forced
  registration timeout and repeated PTY detach/attachment. Eleven unselected
  cases were skipped by the filter, not claimed as executed.
- The forced timeout observed `offline: Native worker failed: did not register
  in time`, with RPC start reasons `[]`. It independently asserted that the real
  worker PID no longer existed, its new copy was absent and the original file's
  bytes were unchanged. Reopen and retry-fork registration also succeeded.
- Final TypeScript and six-file Biome checks passed; diff checks passed.
  Independent scoped review found no blocker, including the final false-discard
  expectation and the saved test outputs.

Evidence is in `/private/tmp/pocketpi-paged-production.oge2ybh1/`:
`native-start-red-01.log`, `native-start-green-01.log`,
`native-start-lifecycle-01.log`/`-02.log`, `native-start-tsc-02.log` and
`native-start-real-pi-01.log`. The real-Pi run completed in 16.94 seconds; the final
lifecycle run completed in 6.57 seconds. All root test handles reached terminal
exit status. No personal Pi configuration, external provider, remote deployment
or external Git operation was used by the fixtures.

This is a local Native startup/cleanup correction, not complete Android, physical
Samsung, independent Linux or whole-environment qualification. Existing paged and
positive-outcome public gates remain disabled pending the remaining Native
criteria and reviewed production wiring. No new RPC feature or matrix was added.
