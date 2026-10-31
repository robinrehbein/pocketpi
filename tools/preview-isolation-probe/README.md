# Throwaway WebView isolated-process feasibility probe

This standalone debug APK does not alter or depend on PocketPi runtime code. A launcher activity binds two otherwise identical services. The control service runs under the app UID, the isolated service uses `android:isolatedProcess="true"`. Each reports its UID, INTERNET permission, early WebView suffix bootstrap, WebView construction, and intercepted static HTML load. It never supplies a network endpoint or credentials. A missing callback or crash is an inconclusive failure and must be investigated in logcat; it is not a security success.

Using the already installed PocketPi wrapper, with Android SDK and dependencies already cached:

```sh
cd tools/preview-isolation-probe
../../gradlew --offline --no-daemon assembleDebug
adb install -r build/outputs/apk/debug/preview-isolation-probe-debug.apk
adb shell am start -n de.pocketpi.previewprobe/.ProbeActivity
adb logcat -s PreviewProbe:I AndroidRuntime:E chromium:E '*:S'
```

Expected useful result: `CONTROL ... internet=true`, `CONTROL FINAL loaded="probe-ok"` and a different isolated UID with `internet=false`, followed by either a `FINAL loaded="probe-ok"`, an explicit bounded error, or process death/timeout. Capture API level, WebView package/version and full relevant crash details. `adb shell dumpsys webviewupdate` identifies the active WebView provider. The 15-second activity watchdog is informational, not a verdict; report each service's `FINAL` separately. The `blockNetworkLoads` setting or API-level behavior can invalidate the control; do not interpret isolated failure when control fails as proof of isolated-process incompatibility.

This gate covers only service process startup, UID/permission and a static page. It does not test embedding, HTTP, fetch, WebSocket, HMR, cookies, storage, revocation, bypasses or a firewall. On API 26–27 no suffix is set: this probe does not claim multi-process WebView support there.

## Observed local result, 2026-10-31

The offline build passed with AGP 9.4.1, Kotlin plugin classpath 2.4.21, Gradle 9.8.2 and Java 25.
The Kotlin plugins align AGP's transitive dependency versions with PocketPi's existing cache;
this probe itself uses Java and has no dependency on PocketPi code. This directory is not included
in PocketPi's Gradle settings and does not add an exported component to the product APK.

On the isolated API 36 emulator, WebView provider `com.google.android.webview` version
`133.0.6943.137`, the control process (UID 10215, INTERNET granted) created the WebView and loaded
`probe-ok`. The isolated process (UID 99000, INTERNET denied) bound successfully and completed
suffix bootstrap, but WebView construction failed with `NullPointerException`: the platform
window manager interface was null when querying `hasNavigationBar(int)`. This is evidence that this
specific service/context approach fails on this runtime, not proof that every isolated renderer
approach or WebView version is impossible. It does not qualify UI embedding or a network bridge.

Root logs: `/private/tmp/preview-isolation-probe-build-final.log`,
`/private/tmp/preview-isolation-probe-run.log` and
`/private/tmp/preview-isolation-webview-provider.log`. No network endpoint, pairing secret or
user data was provided. A later Chromium renderer-exit message followed control teardown; the
control's successful document callback was recorded before it.

Do not ship a same-UID preview process as a replacement security boundary based on this failure.
The full preview needs a separately proven renderer/network boundary and authenticated bounded
HTTP/WS transport, or a separately constrained host renderer. API 26–35 and physical Galaxy devices
remain untested by this probe.
