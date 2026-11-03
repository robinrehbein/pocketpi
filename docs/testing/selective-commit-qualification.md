# Selective commit: encrypted Android fixture qualification

On 2026-10-31, the actual SecureRemoteTransport, repository, Changes-pane entry and commit-review
controller completed selective commits against a local Mac HostDaemon and real disposable Git
repositories. Each fresh fixture began with changed a.txt and b.txt. The test selected only a.txt,
confirmed its message, and checked the host: exactly a.txt entered the new commit with its expected
content; b.txt retained its previous HEAD content and remained dirty in the worktree.

## Observed results

| Display profile | Resource language | JUnit result | Duration | Visual evidence |
| --- | --- | --- | --- | --- |
| 1080 x 2424, 420 dpi | English | OK (1 test) | 26.902 s | Selection, confirmation and success inspected |
| 1080 x 2424, 420 dpi | German | OK (1 test) | 13.443 s | Selection, confirmation and success inspected |
| 2560 x 1600, 320 dpi | English | OK (1 test) | 26.082 s | Fresh frame-synchronized rerun; all three images inspected |
| 2560 x 1600, 320 dpi | German | OK (1 test) | 10.214 s | Selection, confirmation and success inspected |

These are two display configurations on one privately owned API-36 arm64 emulator, not separate
physical Samsung devices. The emulator was created for this run, used its own ADB server on 5187
and console/ADB ports 5584/5585, and was identified before use. Its ADB server and emulator were
stopped after the run; the default ADB server was never addressed. Fixture credentials were
private, transferred with shell-only permissions, and removed after use. All five fixture servers
(including the superseded English-tablet run) were stopped. No user repository or real Git remote
was used.

An initial English-tablet run passed in 14.648 s, but its confirmation screenshot still contained
the previous selection frame. That image is not confirmation evidence. The screenshot helper now
waits for Compose idle, registers a View frame-commit callback, requests a redraw and waits for the
bounded callback before capture. A fresh repository and fixture produced the replacement result
above. No arbitrary screenshot delay was added.

## Scope and reproducibility

The fixture source is in the host repository at
packages/pi-atlas/extensions/remote/runtime-test/git-android-e2e/. The opt-in Android test is
GitCommitTransportEndToEndTest. Product app base: 219a47c; host base: e0683b3c1 with the local fixture
and current source changes. Node 24.18.0, Apple Git 2.54.0 and SDK Emulator 37.1.11 were used.
The session is a synthetic idle LocalPeer. The test renders real panes in a targeted activity
harness; it does not establish the full RemoteScreen navigation or a real Pi runtime.

Local logs, metadata and images are retained under
/private/tmp/pocketpi-commit-e2e.7bsGDw/. Canonical English-tablet images are in
tablet-en-frame-images/, not tablet-en-images/. The temporary path is local evidence, not a
portable release artifact. The test APK was rebuilt successfully after the frame synchronization
change.

Still open: actual full-app navigation, real Pi worker integration for this workflow, lost ACK
and reconnect without replay, post-CAS index-install failure through the encrypted Android route,
process-death recovery, independent Linux/devcontainer execution, and physical Galaxy S25/Tab S11
acceptance including hardware input and DeX. Unit and protocol tests for several of these failure
states do not replace these runtime qualifications. Push, pull request and complete ticket-to-CI
qualification remain separate work.
