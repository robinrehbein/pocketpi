# Production entry and live-run acceptance

## Intended result

Qualify the real MainActivity, RemoteApplication-created repository, ProcessLifecycleOwner and
LiveRunService on the existing private API-36 emulator. Keep the real SecureRemoteTransport;
inject only a narrowly scoped local fixture CA through a one-shot debug factory. Release,
benchmark and Wear behavior remain unchanged.

## Preconditions and boundaries

Root verifies the dedicated ADB server, owned AVD and fresh owned app data before instrumentation.
An externally created private marker is required before seeding absent pairing files. Never
overwrite an existing pairing, install a system CA, replace the application repository or replace
LiveRun.states. Instrumentation must prove its bootstrap ran before Application.onCreate.
The local native provider emits a real additional delta only on an explicit fixture action;
separate provider evidence does not substitute for Android receipt/render evidence.

## Sequence

1. Review the optional Host fixture delta and debug-only Android bootstrap independently.
2. Compile both candidates; probe the actual cold-start callback order on API 36.
3. Open the native session through MainActivity, then verify actual Activity pause/stop,
   ProcessLifecycleOwner STOP, real foreground service and notification before triggering output.
4. Observe the new delta in the real repository while backgrounded; return to the same Activity,
   session, worker and assistant message, with exactly one marker and no duplicate prompt.
5. Check the observed background state with Live Run disabled, restore settings, disconnect and
   remove only the explicitly owned fixture state. Keep precise failed-attempt evidence.
6. Run appropriate broader checks after source freezes; record APK/source pins and actual evidence.

This is emulator/debug acceptance with a deterministic local provider, not physical Samsung,
release TLS, FCM, an external provider or an independent remote Linux qualification.

## Result

The final phone and tablet-layout acceptance cases pass with identical frozen sources/APKs,
mandatory lifecycle history and independent artifact review. The actual API-36 cold-start
bootstrap, service notification, connected background delta and same-Activity/session/worker
handoff are established. The required checks pass 3,538 App tests; the unchanged 34-test Wear
result is up to date. See [qualification](../testing/production-entry-live-run-qualification.md)
for exact evidence, failed test-harness attempts and remaining boundaries. The overall goal
remains active.
