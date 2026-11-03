# PocketPi device acceptance

This checklist accepts PocketPi as a mobile development environment on real hardware: a Galaxy
S25, a Galaxy Tab S11, a Wear OS watch, the Mac host, an independent Linux host and a
devcontainer with a bind-mounted project. CI's emulator tests do not cover Samsung power
management, real networks, push delivery or a second physical device. This checklist covers them.

The phone stages follow the order of the emulator journey
[`CombinedNativeJourneyEndToEndTest`](../../src/androidTest/java/de/joinnoah/pi/remote/CombinedNativeJourneyEndToEndTest.kt).
Quoted labels are the English UI strings. Steps marked *(optional)* can be skipped without
blocking acceptance.

| Block | Time |
| --- | --- |
| 0. Preparation | 10 min |
| 1. Phone core workflow (S25, Mac host) | 30 min |
| 2. Robustness (S25, Tab S11) | 20 min |
| 3. Tablet and Samsung specifics (Tab S11) | 10 min |
| 4. Watch | 10 min |
| 5. Linux host and devcontainer *(optional within the 90 min; needs a prepared host)* | 20+ min |

Record the app version (Settings › About), the host commit and the date at the top of the PR or
note you fill in. A failed box gets a one-line note with what was shown.

## 0. Preparation

Install and pair as described elsewhere. This runbook does not repeat those steps:

- Build and install: [README](../../README.md#build-and-install), or the Play testing build from
  the [Play testing runbook](play-testing-release.md). Push needs a build with the
  `PI_REMOTE_FIREBASE_*` values ([README, Firebase](../../README.md#firebase)). A debug build
  without them shows "This build cannot register for push notifications, so it shows none."
- Watch install and pairing: [Wear OS](../wear-os.md#pairing).
- Host setup: `packages/pi-atlas/extensions/remote/README.md` in noah-monorepo, sections
  "Configure the Mac", "Run the host on Linux" and "Configure the host browser".

Host switches and grants. Run these with `pnpm pi-remote:host` on the host, which must be running
for every `grant`. **Grants are per device ID**, so the phone, the tablet and the watch each need
their own. If a feature works on the phone but not on the tablet, check this first.

```sh
pnpm pi-remote:host devices list                     # device IDs of phone, tablet, watch
pnpm pi-remote:host config bash-commands on          # ! shell commands
pnpm pi-remote:host config file-writes on            # editor save, New file / Rename / Delete
pnpm pi-remote:host config git-writes on             # commit, push, pull request
pnpm pi-remote:host config terminal-control on
pnpm pi-remote:host terminal grant <device-id> <project-id>
pnpm pi-remote:host config browser-executable /absolute/path/to/chromium   # host stopped
pnpm pi-remote:host config browser-control on
pnpm pi-remote:host browser grant <device-id> <project-id>
pnpm pi-remote:host config preview-control on
pnpm pi-remote:host preview grant <device-id> <project-id> <port>
pnpm pi-remote:host config emulator-control on
pnpm pi-remote:host emulator profile set /absolute/path/to/profile.json
pnpm pi-remote:host emulator grant <device-id> <project-id> <profile-id>
```

The emulator profile format is in [emulator integration](../testing/emulator-integration.md#implemented-path).
Pull requests need the GitHub CLI signed in on the host, and the project needs an `origin` on
GitHub. Use a throwaway repository or branch: the run creates a real commit, push and pull request.

- [ ] **0.1** `pnpm pi-remote:host doctor` reports pi, the state directory, Node and that
  `node-pty` loads. **Expect:** no errors. Without `node-pty`, phone-started sessions use RPC and
  the chat has no "Terminal" button.
- [ ] **0.2** S25 and Tab S11 each paired ("Pair computer" › "Scan QR code", approve on the Mac).
  **Expect:** each appears in `devices list` with its own ID and has the grants above.
- [ ] **0.3** In a scratch project, start a dev server on the granted preview port.

## 1. Phone core workflow (S25, Mac host)

- [ ] **1.1 Session.** Open the shared project and tap "New session". Send a short prompt.
  **Expect:** the green dot shows the session ready, the reply streams in, and tool calls appear
  as expandable cards.
- [ ] **1.2 Busy controls.** Send a longer task. While it runs, type a message and use "Steer now",
  then another with "Send later". Then "Stop pi". **Expect:** "Sending now, into the running step: …",
  then "Sends when pi is done: …". If a queued message would be discarded, Stop asks first.
- [ ] **1.3 Editor.** "Files" › a text file › "Edit". Change a line and tap Save.
  **Expect:** saved. Edit the same file on the Mac, then save on the phone again. **Expect:** the
  app offers to reload or to send the edit to the agent as a diff. Nothing is overwritten.
- [ ] **1.4** *(optional)* An extension editor question shows "Load editor". Load it, edit,
  then "Send". **Expect:** the question closes and pi receives the edited text.
- [ ] **1.5 Shell.** Send `!git status`. **Expect:** a "Shell command" card with output and
  "Exited with code 0". `!!` runs the same without sharing it with pi.
- [ ] **1.6 Terminal.** Tap "Terminal" in the chat header, run `ls`, then "Close".
  **Expect:** output renders, the Ctrl/Alt/F-key shortcuts work, and closing keeps the worker
  running.
- [ ] **1.7 Commit.** "Changes" › "Prepare commit". Tick the edited file, enter a "Commit message",
  then "Review commit" › "Confirm commit". **Expect:** "Commit created on <branch>: <sha>".
- [ ] **1.8 Push.** "Changes" › "Push branch" › "Review push" › "Confirm push". **Expect:**
  "Branch pushed: …" or "Branch created on the remote: …". The commit shown matches step 1.7.
- [ ] **1.9 Pull request.** "Changes" › "Create pull request". Choose the base, set Title and
  Description, switch off "Create as draft", then "Review pull request" › "Create pull request".
  **Expect:** "Pull request #N created", and the PR exists on GitHub.
- [ ] **1.10 PR/CI status.** Back in the chat, the chip shows "PR #N" with "checks running", then
  "checks passed" (or failing checks) once CI finishes. **Expect:** "Open checks" and
  "Open pull request" open the right pages.
- [ ] **1.11 Browser.** Actions menu › "Browser" › "Take control" › "New tab". Enter an
  `https://` address and tap "Go", type into a page field via "Text to send" › "Send".
  **Expect:** "You have control", the page loads and the text arrives once.
- [ ] **1.12 Preview.** Close the browser. Actions menu › "Preview", enter the "Port", tap "Open",
  then "Take control". **Expect:** the dev server's page from step 0.3 renders as "Preview · <port>".
- [ ] **1.13 Emulator.** Actions menu › "Emulator" › "Choose a profile" › the profile. The first open
  boots the AVD and can take up to two minutes. Then "Take control" › "Home". **Expect:** the screen
  image updates and Home reaches the AVD. Opening alone joins as a reader.
- [ ] **1.14 File create.** "Files" › a folder › the more-actions menu › "New file", enter a "File
  name", tap "Create". **Expect:** "Done: Create <name>" and the new file opens.
- [ ] **1.15 Rename refused.** Create `taken.txt` on the Mac. Rename the new file to `taken.txt`.
  **Expect:** "A file of that name exists already." or "Not done: the destination exists already."
  Both files are unchanged.
- [ ] **1.16 Rename and delete.** Rename to a free name, then "Delete". **Expect:** the dialog names
  the exact path ("This deletes … on the computer. It does not go to the trash."). After it, the
  file is gone on the Mac.
- [ ] **1.17** *(optional)* Tickets: computer options menu › "Tickets" › a ticket › "Start with
  agent" › "Start". **Expect:** the steps "Preparing worktree" … "Sending prompt" reach "Done" and
  "Open chat" opens the new session.

## 2. Robustness

Use one running session on the S25 unless the step says otherwise.

- [ ] **2.1 Wi-Fi off/on mid-run.** While pi streams, turn Wi-Fi off for 20 s (with mobile data
  off too), then on again. **Expect:** "Offline" or "Waiting for network…", then "Reconnecting
  in N s" / "Reconnect now", then the chat catches up without duplicate messages.
- [ ] **2.2 Wi-Fi to mobile data.** Mid-run, turn Wi-Fi off with mobile data on. **Expect:** the app
  reconnects on its own over mobile data and the run continues on screen.
- [ ] **2.3 Offline prompt.** With the network off and pi idle, send a plain prompt.
  **Expect:** "Queued, sends when connected". After the reconnect it goes out once. If the chat
  moved on meanwhile, it shows "Not confirmed" and the text returns to the composer.
- [ ] **2.4 Terminal through a drop.** With "Terminal" open, toggle Wi-Fi. **Expect:** "Connection
  lost.". Reopen it by hand. Nothing typed earlier is replayed.
- [ ] **2.5 Background.** Mid-run, go home for 2 min. **Expect:** an ongoing "pi is working"
  notification (Samsung: also the Now Bar, unless "Live Updates are turned off…" is shown in
  Settings). On return the chat is current. "Stop updates" ends the notification.
- [ ] **2.6 Killed and restored.** With a draft typed, swipe the app from Recents, or use Settings ›
  Apps › PocketPi › Force stop. Reopen. **Expect:** the same chat, the draft intact, and a send
  without an acknowledgement shown as "Not confirmed", never resent.
- [ ] **2.7 File operation outcome unknown.** Start a Delete and immediately turn Wi-Fi off, or
  force-stop while it shows "Waiting for the computer: …". Reopen Files. **Expect:** "Unknown
  outcomes" lists it. "Check again" resolves it, or "Acknowledge" clears it without repeating it.
- [ ] **2.8 Emulator control through a drop.** Hold emulator control with no key in flight and
  toggle Wi-Fi. **Expect:** after the reconnect the pane has control again without tapping
  "Take control", and "Home" is accepted. The emulator pane must stay visible during the drop.
- [ ] **2.9 Second device keeps control.** Repeat 2.8. While the S25 is offline, tap "Take control"
  on the Tab S11. Turn the S25's Wi-Fi back on. **Expect:** the tablet keeps control and the S25
  stays a reader with no "Another participant has control" notice.
- [ ] **2.10 Drop during input** *(optional)*. Tap a key and drop Wi-Fi at once. **Expect:** "The
  last operation may have completed. Reconnect to continue." Control is given up and the key is
  not replayed.
- [ ] **2.11 Phone to tablet.** Phone and tablet are separate paired devices. Nothing is transferred
  between them, and drafts stay on the device where they were typed. Mid-session on the S25, open
  the same session on the Tab S11. **Expect:** same messages, live updates on both, and Stop or a
  follow-up from the tablet works. In "Terminal", the tablet shows "Terminal is in use on another
  phone." and "Take over" moves it, while the S25 shows "Terminal control was taken over
  elsewhere.". The browser hands over with "Give control" / "Take control".
- [ ] **2.12 Mac sleep and wake.** With no turn running, put the Mac to sleep (Apple menu › Sleep)
  for 2 min, then wake it. **Expect:** sessions go "Offline", and after wake "Refresh" or the next
  foreground reconnects. During a phone-started run the host holds `caffeinate -i`, so idle sleep
  does not stall it. Lid-closed on battery is not covered.
- [ ] **2.13 Relay or host drop.** Turn the Mac's network off for 30 s, or run `pnpm pi-remote:host
  stop` then `start`. **Expect:** the app shows the computer offline, then reconnects and validates
  the selected session. A stopped host costs a running native session's work.
  "Disconnect (keeps pairing)" in the computer options menu is a client-side disconnect, not this
  test.

## 3. Tablet and Samsung specifics

- [ ] **3.1 Tablet layout.** On the Tab S11 open a chat. **Expect:** the inspector shows "Changes",
  "Files" and "Terminal" as tabs beside the chat. "Widen side pane" / "Narrow side pane" work.
- [ ] **3.2 Split screen.** Put PocketPi next to another app, resize the divider, and open the
  artifact preview, a picker and dictation. **Expect:** no reload. The open preview, picker and
  dictation stay.
- [ ] **3.3 DeX / free-form window.** Start a run in a DeX or pop-up window, minimise it for 1 min,
  then restore it. **Expect:** the run kept streaming (live-run notification shown). The app does not
  reload into a blank chat. Settings › "Layout" › "Reset layout" restores the column widths.
- [ ] **3.4 Hardware keyboard** *(optional, Book Cover keyboard)*. In the editor, Ctrl+S saves,
  Ctrl+F finds, and Esc closes find.
- [ ] **3.5 Battery optimisation.** PocketPi asks for no battery exemption. Leave the S25 on the
  default ("Optimized") and repeat 2.5 with the screen off for 10 min. Note the result. Then set
  Settings › Apps › PocketPi › Battery › "Unrestricted" and repeat. **Expect:** completion and
  question pushes arrive in both cases. Note any difference.
- [ ] **3.6 Push when locked.** Lock the S25. Let a run finish and another ask a question.
  **Expect:** "pi has finished" and "pi needs an answer" arrive on the lock screen. Tapping opens
  the right session after unlock. Reply on a completion works only when the phone is unlocked.
- [ ] **3.7 App lock** *(optional)*. Settings › "App lock" on. **Expect:** "Unlock PocketPi" on start
  and after the timeout.
- [ ] **3.8 Samsung font.** **Expect:** UI text uses the Samsung system font, and code stays
  monospace.

## 4. Watch

The current watch app is a separate client with its own pairing ([Wear OS](../wear-os.md)). Use
the checks in [Required Galaxy Watch Ultra checks](../wear-os.md#required-galaxy-watch-ultra-checks)
for depth. In this run, check only:

- [ ] **4.1 Pairing.** "Pair computer" › "Scan pairing code on phone", scan on the S25, then approve
  the watch on the Mac. **Expect:** the watch lists the shared projects.
- [ ] **4.2 Chat and question.** Open the running session and answer a question.
  **Expect:** the answer reaches pi.
- [ ] **4.3 Stop.** "Stop pi" asks for confirmation, then stops the run.
- [ ] **4.4 Status tile.** Add "PocketPi status". **Expect:** up to two sessions that need you or
  are working, with their pull request checks and age. Tapping a row opens that session.
- [ ] **4.5 Watch notifications** *(Firebase build)*. Enable "Watch notifications".
  **Expect:** a completion arrives on the watch, independently of phone mirroring.
- [ ] **4.6 Open on phone.** "Open on phone" opens the same session on the S25.

**Pending: wear feature-set PR.** A pull request that extends the watch is in progress. Confirm
these items once it lands, and replace this list with real steps and labels:

- [ ] Status tile and a watch-face complication. The tile exists, and no complication exists yet.
- [ ] Notifications with actions.
- [ ] Approve / deny from the notification or the watch. Today questions are answered in the app.
- [ ] Quick reply. Today the watch has no notification reply.
- [ ] Stop a run from the notification or the tile. Today it is in the app only.

## 5. Linux host and devcontainer

Setup lives in noah-monorepo: `packages/pi-atlas/extensions/remote/README.md` ("Run the host on
Linux") and `packages/pi-atlas/deploy/linux-host/README.md` (core image and Dev Containers adapter).
Repository qualification so far is local and container-only
([Linux host](../testing/linux-host-qualification.md), [Linux container](../testing/linux-container-qualification.md)).
This run is the first physical-device evidence for both.

### Independent Linux host

- [ ] **5.1 Setup.** On the Linux machine: `setup <relay> <pi>`, `start`, `status`. **Expect:**
  `~/.pi/remote/secrets.json` has mode 0600. `autostart` is refused on Linux, so restart by hand
  with `start`.
- [ ] **5.2 Native mode.** `doctor` reports that `node-pty` loads. Pair the S25 with the Linux host
  as a second computer. **Expect:** a phone-started session offers "Terminal".
- [ ] **5.3 Core workflow.** Repeat 1.1, 1.3, 1.5, 1.6, 1.7–1.10 and 1.14–1.16. **Expect:** as on the
  Mac. File create, rename and delete have not been probed on an independent Linux host before
  this run.
- [ ] **5.4 Robustness.** Repeat 2.1, 2.6 and 2.13 (`stop` / `start`). **Expect:** as on the Mac.
  Linux has no keep-awake, so skip 2.12 or note how the machine's own sleep behaves.
- [ ] **5.5** *(optional)* Browser, preview and emulator. These need a configured Chromium and an
  emulator profile with a `discoveryDirectory`. If the host lacks them, mark the items N/A.

### Devcontainer with a bind-mounted project

- [ ] **5.6 Setup.** Build the core image, run `prepare-devcontainer.mjs` with `--workspace` set to
  the project on the Mac, then `run-devcontainer.mjs`. Then `docker exec … pocketpi-host setup` and
  `status`. **Expect:** the project is at `/workspace` and the state volume is separate.
- [ ] **5.7 Bind-mount file operations.** From the phone, edit and save a file, then New file,
  Rename and Delete in `/workspace`. **Expect:** each change appears in the Mac's checkout with the
  right owner. A Mac bind mount has not been probed for file operations before this run.
- [ ] **5.8 Shell, terminal and git.** Repeat 1.5–1.9 inside the container. **Expect:** as on the
  Mac. Git identity and `gh` must be configured inside the container.
- [ ] **5.9 Restart.** `docker stop --time 30 <container>`, then rerun the driver with the same
  profile. **Expect:** same host identity and pairing (same state volume), and the phone reconnects.
- [ ] **5.10** Browser, preview and emulator are N/A: the core image has no Chromium and no
  Android SDK. *(Optional with the experimental `Dockerfile.browser`.)*

## Result

Acceptance holds when every non-optional box in blocks 1–4 is ticked on the S25 and Tab S11 with
the Mac host. Block 5 accepts Linux and devcontainer hosts separately. Record failures as issues
or PR notes with the step number. A built APK or green CI does not replace any of these steps.
