# Production entry and live-run qualification

## Actual entry path

The final phone and tablet-layout emulator runs use the real `RemoteApplication`, its constructed
`DefaultRemoteRepository`, the internal launch alias, `MainActivity`, `ProcessLifecycleOwner` and
`LiveRunService`. Neither the repository nor `LiveRun.states` is replaced. The existing
`SecureRemoteTransport` talks to an isolated TLS relay and a real native Pi worker.

An explicit instrumentation opt-in installs a one-shot debug transport factory with the fixture
CA. It accepts only the exact local emulator relay origin and peer path, retaining normal hostname
verification. The default debug factory and release transport remain unchanged when no fixture
factory is installed. This does not qualify release TLS or a production relay.

Before resetting or seeding app data, the private runner verifies ADB port 5191, emulator-5584,
the owned `PocketPi_Paged_Private` AVD, emulator status, the debuggable package and both installed
APK hashes against the builds. It resets only that owned test app. A consumed private preflight
marker and absent pairing files are required before seeding the actual encrypted pairing store,
before Application.onCreate constructs the repository. The marker alone is not ownership proof.

## Final acceptance evidence

Both cases pass, including cleanup and mandatory callback-proof validation. Evidence is under
`/private/tmp/pocketpi-paged-production.oge2ybh1`:

| Case | Label | Driver duration |
| --- | --- | --- |
| Phone | `production-entry-final-phone-01` | 26.694 s |
| Tablet layout | `production-entry-final-tablet-01` | 19.124 s |

Independent review matched all 18 source snapshots, the runner snapshot, both built APKs and
captured installed APKs against their hashes and current sources. Both cases use identical
sources, runner and APKs. The tablet wrapper records 1600 × 2560 at 240 dpi and restoration to
1080 × 2400 at 420 dpi.

The sanitized callback proof retains ordered milestone history. It proves the actual API-36
Activity pause/stop and process background state, an active foreground service with notification
ID 5, and a connected repository. An explicit test action then makes the native provider push a
new text delta in the same active turn; its separate PID/session/marker evidence matches the
native owner. Android receives the marker while the Activity and process remain backgrounded
and the service remains present. After return, the same Activity, worker, session and assistant
message remain, with one marker, one matching rendered semantics node and one prompt request.
The service ends with `FOREGROUND`, while the connection remains active.

With Live Run disabled, twenty samples over two seconds observe no service or wanted state.
That bounded observation does not exclude a shorter transient service start. Settings are
restored, the repository is disconnected and the owned fixture/app state is cleaned up.

The final required quick check passes 3,538 App tests; the 34-test Wear result remains valid and
its unchanged task is up to date. Logs are `/private/tmp/pocketpi-production-entry-final-quick-check.log`
(89 seconds) and `/private/tmp/pocketpi-production-entry-final-apks.log`. Host fixture type and
format checks pass in `/private/tmp/pocketpi-background-delta-formatted-types.log` and
`/private/tmp/pocketpi-background-delta-final-format.log`. No source changed after final
checks/build and before these runs.

The existing default native workflow also passes without the background-delta option:
`production-entry-default-workflow-phone-01` (driver 89.835 seconds, cleanup PASS). Independent
review matches its 33 pinned sources and the same APKs, verifies one held prompt, lost-ACK recovery,
abort/follow-up, file/agent/shell, one local commit/push/PR and a passing controlled check with
matching heads. Four screenshots are retained. This is a regression of the default provider
fixture path in DevelopmentWorkflowTransportEndToEndTest; it does not independently qualify the
Application's default transport bootstrap or production TLS.

## Diagnostic history

The initial runs are retained as failed attempts, not product fixes or final qualification:

- `production-entry-phone-01` and `-02` stalled before the first connection. The custom sleeping
  wait did not advance the Compose test dispatcher.
- `-03` established the received alias, paired host and nonblocking project lookup. Its artificial
  save-state probe changed ComponentActivity's lifecycle to CREATED.
- `-04`, after scheduler advancement, connected and selected the session but the artificial
  save-state call excluded its root from Compose's resumed-root registry.
- `-05` passed after removing that state-changing probe, but still contained a diagnostic project
  lookup. The final source removes that lookup entirely, so the final cold-start evidence does not
  depend on warming the project-hint cache.

No production MainActivity, repository or service change was needed to resolve these test-harness
problems. The private opt-in background-delta provider extension preserves the existing lifecycle
trace and records separate evidence; a provider push alone is not an Android receipt proof.

## Boundaries

This qualifies the stated short background/return path on an owned API-36 emulator with a
deterministic native provider. It does not establish physical Galaxy behavior, prolonged Doze,
FCM, production relay/provider behavior, process death through MainActivity, a fresh-device
handoff or an independent Linux host. The earlier process-recovery tests retain their separate
NavigationTestActivity scope. Wear emulator testing remains excluded. The overall goal is active.
