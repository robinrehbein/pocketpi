# Native custom terminal dialog qualification

**Goal:** answer a real public Pi custom component through PocketPi's existing terminal, retaining the same native worker/session and confirmed input ownership.
**Spec:** expanded remote-development goal and criterion 4b in `docs/remote-development-goal.md`.
**Architecture:** optional private fixture command opens a deterministic custom TUI with visible nonce and two choices. The actual Application/MainActivity opens the production terminal and explicitly confirms takeover if necessary. Touch keys select and submit; a private source trace proves the exact result. Existing remote custom-dialog forwarding and terminal guards stay unchanged.
**Constraints:** public Pi APIs only; no model call, external service writes, personal Host, or Pi core changes. Private emulator/ADB only. Existing unrelated fixture modes must stay compatible. Source/APK/runner snapshots and cleanup required. A passed fixture does not imply arbitrary custom component, real Samsung, independent Linux or iOS qualification.

## Tasks

- [x] Add `--native-custom-dialog` mode to the existing local fixture, requiring native worker and excluding conflicting workflow/editor modes. Add a separately scoped public extension command `/probe-custom <nonce>`; record opened/rendered/input/result events boundedly with native PID/session. Metadata `customDialog` includes `command`, `nonce`, `title`, `expectedValue`, `followupCommand`; status includes independently validated `opened`, `rendered`, `resultVerified`, `followupVerified`, `workerVerified`, `valid`. Prove a later normal public command in the same process after closing/releasing terminal. Do not infer input success from dispatch ACK.
- [x] Add opt-in actual MainActivity instrumentation: `productionEntryCustomDialog=true`. Use existing real bootstrap and SecureRemoteTransport. Dispatch command once in composer, open terminal through actual action, confirm takeover if shown, inspect actual WebView xterm rows and rendered pixels/screenshot, use touch down/Enter, await exact native result, close terminal and send followup once. Persist bounded structured proof in app files. No fake repository/terminal or direct SDK callback.
- [x] Root owns build, fixture/emulator execution and private controller. Observe original failure before modifying product behavior; add regression if a real defect emerges. Run phone/tablet and existing compatibility, inspect screenshots and independently audit inputs/results. Record limits, cleanup and local commits.

## Rulings

Proceed under existing autonomous implementation approval; this is the already specified custom-dialog acceptance path, not a new product direction. Root alone runs shared runtimes. Host fixture and Android test authors may work in parallel on disjoint files and the metadata contract above. Default flags remain unchanged.
