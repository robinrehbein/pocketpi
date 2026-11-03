# Native editor Source peer replacement through real SDK reload

> Disjoint Terra authors; root owns Git, builds, runtime, audit and commits.

Goal: prove that an old Android upload cannot affect a replacement Source peer
when Pi's extension runtime reloads while the Pi process stays alive, then resume
explicit Android work through the new bridge.

## Feasibility and scope

The normal Host `session.reload` path refuses an open editor as busy. Source
worker-command guards also reject native-dialog activity. Do not disable those
guards or claim a successful Host reload during the editor.

A separate private test trigger uses the actual running fixture command's public
`ExtensionCommandContext.reload()` API from a public terminal-input handler. The
fixture consumes Ctrl-R and calls that API asynchronously; it does not patch Pi
core or replace IPC peers/maps manually. This is an SDK-initiated extension reload
fault/compatibility scenario, not a production Ctrl-R feature or successful
Host/phone reload. Its feasibility and old SDK outcome must be established by
actual execution, not assumed from static code.

The old fixture UI-editor wrapper is disabled only in this mode to avoid nested
wrapper restoration across reload. Capture immutable SDK invocation identity
before awaiting it, and record actual return or error after invalidation. Record
real shutdown/start generations rather than fabricating a reconnect.

## Work

- [x] Read-only feasibility: normal busy guard and public SDK reload path.
- [x] Native-only private fixture, opt-in Android test, source/APK-pinned controller.
- [x] Hold the actual last old Source chunk ACK after original acceptance of the
      complete 1-MiB Android value, with its possible-write request journaled.
- [x] Probe actual Host reload and verify busy, unchanged old authority and no
      Source reload dispatch. Separately invoke real SDK reload via owned PTY.
- [x] Observe actual shutdown/start, distinct registered Source peer/bridge,
      unchanged PID/runtime/file/session and invalid old capture/lease/question.
      A same-process re-registration may preserve the journal epoch; do not
      require a process replacement or a new epoch as a substitute for peer proof.
- [x] Release the original unchanged ACK; correlated old error, zero old Source
      commits or old submitted-value result, quota cleanup before expiry, exact
      draft and uncertain attempt preserved. Record actual old SDK undefined or
      error separately; never invent a result to satisfy the test.
- [x] Fresh explicit Android command through the new extension/peer; empty Source
      prefill and distinct new question/grant/lease/upload/request, real receipt
      and 32-byte SDK result in the same process. Retain old recovery entries and
      observe a stated no-replay interval.
- [x] Scoped review, build/type/format checks, actual runtime and independent
      source/SDK/authority/cleanup audit; record failed diagnostics and limits.
- [x] Local commits and update qualification map; public paged/outcome gates off.

Further gates retain their scope: positive-outcome owner incarnation across
replacement, silent lease-only revocation/same-question reacquisition, physical
Samsung/DeX, independent authorized Linux and the other full environment criteria.

Qualified scope: [SDK peer reload](../../testing/native-editor-peer-reload.md). Next mandatory enablement gaps are separated in the [contract checkpoint](../../testing/paged-editor-enablement-checkpoint.md); positive outcomes remain off.
