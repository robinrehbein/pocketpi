# Native Android command delivery proof

Goal: prove an Android composer command reaches the same live Host-managed Pi TUI session,
without a model turn, owner state or a Pi core patch. This closes the command-delivery seam;
it does not qualify raw PTY keystrokes, custom dialogs or model reasoning.

1. Add a fixture-only public Pi command with a per-run nonce and separate private trace of
   nonce, PID, session and mode. Publish `inputVerified` only for exactly one matching trace
   and the current verified native worker. Preserve all existing status phases and cleanup.
2. Add an explicitly opted-in Android test path. Require native mode, type the metadata
   command and nonce through the actual composer, send exactly once and await the verified
   handler trace. Dispatch acknowledgement alone is insufficient.
3. Root reviews and runs final types/format and the real native Android test on phone/tablet.
   Preserve pending/failure/pass/clear/reconnect assertions. Agents do no shared runtime work.
4. Record actual evidence and limits, clean owned runtime and credentials, and commit locally.
   No push, release, GitHub mutation or paid model call.

Baseline: App `1203f70`, Host fixture `357987faf`. The earlier four-profile native status and
external RPC regression are saved separately. Two composite Git regression tests remain under
investigation; a timed-out run is not a pass and no timeout or production guard is relaxed.


Functional qualification passed: English phone 32.610 s, German tablet 19.680 s, exactly-one
handler trace in the same current native process/session, then all status/reconnect assertions.
Types/format and test APK assembly passed; owned runtime and temporary credentials were cleaned.
Tablet IME-open recovered screenshot shows a Changes header/card overlap: visual diagnosis is
pending. See [qualification](../testing/pr-status-parity-qualification.md). No raw PTY or model-turn
claim is made. The isolated Git PR rerun passed in 134.79 s with its original limit; Git-write
passed six tests. The earlier loaded PR timeout remains recorded.
