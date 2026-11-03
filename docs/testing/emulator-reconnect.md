# Owned emulator reconnect qualification — 2026-10-31

The encrypted Android-to-host flow now has a controlled lost-input-acknowledgement test.
No product behavior was changed in this increment.

## Failure injection and evidence

The isolated fixture accepts one Home input through the real owned AVD backend, then destroys
only the device-facing TLS proxy connections before returning the result to the host daemon.
New device upgrades stay blocked until the old participant and owner have been removed. The
fixture's activity observation runs outside the input transaction and confirms that Home actually
changed the resumed Android activity. The old response is discarded; it is never delivered to
the replacement connection.

The phone flow passed in 65.583 seconds at 1080x2400 / density 420. The tablet flow passed in
59.501 seconds at 1600x1000 / density 160. Both use the actual encrypted transport, repository,
Compose pane, host daemon, public Pi tool through local IPC and a separate 1080x2400 target AVD.
Both prove:

- Exactly one accepted native input in the cut interval, with Home observed on the target.
- Removal of the old device participant and control owner.
- Rejoin to the same emulator and incarnation through a new connection identity, as a reader
  with a complete current frame.
- No automatic acquire and no repeated input: host counters remain at the cut baselines until
  the test explicitly requests control again.
- Successful explicit reacquisition with a new-generation frame, followed by grant revocation,
  rejected further device input and confirmed target stop.

A deterministic coordinator regression separately covers the last reader disconnecting with no
agent participant. A valid stable device/session source retains the same runtime independently
of the dead socket; the old actor and lease cannot dispatch. A pre-disconnect acknowledged image
does not authorize post-reconnect coordinates: a new lease and completely acknowledged frame are
required. The focused coordinator/service run passed 19 tests.

The APK builds and extension checks passed (150 warnings, three informational diagnostics).
An independent bounded review found no concrete false-positive in the failure injection or its
assertions. Broad suites were not repeated because this increment changes only qualification
fixtures, tests and documentation. Their previous results and limits remain in
[emulator integration](emulator-integration.md).

## Reproduction

Build the App and instrumentation APKs, start a separate isolated frontend emulator, and configure
an owner-private target profile as described in the integration document. Start the host fixture:

```text
export EMULATOR_E2E_PROFILE_FILE=/absolute/owner/profile.json
export EMULATOR_E2E_FRONTEND_SERIAL=emulator-FRONTEND_PORT
export EMULATOR_E2E_HOME_COMPONENT=EXPECTED_HOME_COMPONENT
node packages/pi-atlas/extensions/remote/runtime-test/emulator-android-e2e/run.ts --reconnect --output /private/tmp/fixture.json
```

Replace the environment placeholders with the owner configuration and actual frontend serial. Transfer the
fixture JSON and its ephemeral CA to the isolated frontend device, replacing `caCertPath` with its
device-local path. Run `EmulatorTransportEndToEndTest` with `emulatorFixturePath` pointing to that
JSON. The fixture JSON contains temporary pairing material and must not be committed or published.
The fixture and test are opt-in; ordinary test runs do not start an SDK emulator.

Root cleanup confirmed both owned emulators, the relay fixture and dedicated ADB server stopped;
target ports, runtime lock and temporary public JWK were absent. Frontend viewport overrides and
temporary device-side fixture files were removed. The existing standard ADB server on 5037 was
unchanged.

## Limits

These are bounded emulated-device flows, not physical Galaxy S25/Tab S11 or DeX qualification.
They do not establish all lifecycle/process-death cases, switching between physical devices,
long sessions, all network failures or host/container crash recovery. The native AVD session
retention test without Pi is deterministic unit evidence; the two actual encrypted flows include
a Pi-tool participant. Physical devices are currently unavailable.

Local evidence: `/private/tmp/emulator-reconnect-device-phone1.log`,
`/private/tmp/emulator-reconnect-device-tablet2.log`,
`/private/tmp/emulator-reconnect-{phone,tablet}-proof.json`,
`/private/tmp/emulator-reconnect-root-focused.log`,
`/private/tmp/emulator-reconnect-check-final.log`.
