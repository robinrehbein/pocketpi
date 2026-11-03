# PocketPi for Wear OS

The `wear` application is an independent pi Remote client for Wear OS 3 and newer
(API 30+), designed for the round display of the Galaxy Watch Ultra. It connects
through WLAN, LTE or the watch's system-provided network. The Mac must remain awake.

Phone and watch share application ID `de.robinrehbein.pocketpi`, but have **separate
installations, device keys, pairings and drafts**. The watch uses the existing
Android protocol, encrypted transport and repository implementation. No host or
relay protocol changes are required.

## Feature selection

The primary development clients are the Galaxy S25 and Galaxy Tab S11. Wear OS
offers a smaller set of interactions chosen for watch use; phone feature parity
is not an acceptance criterion. Its scope is not limited to messages and questions.

For each new feature, assess whether its purpose and current state fit on the
watch display, whether the user can make the decision with the context shown,
and whether input and confirmation remain practical with touch or dictation.
Prefer brief status checks and deliberate actions such as approvals, short
replies, starting a supported task or stopping running work. Longer reading can
use bounded pages where that remains useful; complex editing, terminal work and
spatial browser/emulator interaction should offer phone or tablet handoff.
Availability in the shared repository does not by itself justify a watch UI.

Watch acceptance must include round-screen clipping, large text, navigation,
accidental activation and background/battery behavior. No Wear emulator is
required for the current Android-focused work; local checks do not replace the
physical-watch checks below.

## Watch feature list

What a watch adds to a mobile agentic development environment, and where each lives.
"In app" means one tap from the session's chat; actions always open the app first.

| Feature | Status | Where |
| --- | --- | --- |
| Agent/session status at a glance | Existing | Status tile (two rows, waiting first) |
| Status on the watch face | Added | Complication: waiting or working count; top session with its checks |
| Host connection status | Existing | Connection line on every host-bound screen; tile "Not paired" and age |
| Question, completion, subagent and job notifications | Existing | Opt-in push, routed to the session |
| Failed-run notifications | Not applicable | The protocol has no failure event; `*.stuck` is the closest. Host change first |
| Answer questions, confirm prompts, approve plans | Existing | Questions screen, one question at a time |
| Short replies, dictated prompts | Existing | Composer with dictation; Send, Send now or Send when done |
| Stop or interrupt a run | Existing | Controls, with confirmation; subagents and background jobs too |
| Pull request and CI checks | Existing (tile), added (chat) | Tile row detail; chat shows `PR #189 · 2 failing` |
| Model, thinking, commands | Existing | Controls |
| Short reads: files, diffs, tool output | Existing | Bounded wrapped pages with line review comments |

Not on the watch, and why:

- **Answers or approvals from a notification action.** A notification carries no question text, so
  an Approve button would approve something the user cannot see. Answers need the encrypted
  connection and the full prompt, so they stay in the app.
- **Code editing, terminal, browser and preview panes, emulator control.** They need a keyboard,
  a pointer or a spatial canvas far larger than a round 1.5-inch display; the watch offers phone
  handoff instead.
- **Photos, documents, camera capture and starting ticket tasks.** See below; both need context or
  input the watch cannot show.
- **Glass/frosted surfaces.** The phone's haze treatment stays on the phone. The watch uses dark Wear
  Compose Material chips and ProtoLayout Material, which read better on OLED and save power.

## Small-screen interaction

- Dark Wear Compose surfaces, generous side margins, full-width chips and scroll
  position indicators keep actions away from the round display's corners.
- Swipe right or use Back to return. Rotary events scroll the current list; the
  Galaxy Watch Ultra's physical controls must still be verified on the device.
- Chat shows the most recent messages first, as compact previews. Tap a message
  for a reader, quoting, tool output or branch actions. Older items are revealed
  in batches of twenty; more history is requested only on an explicit tap.
- Readers wrap text and code instead of requiring horizontal scrolling. Blocks
  are limited to 100 UTF-16 units and three explicit line breaks, with ten blocks
  per page. Surrogate pairs are not split. File and diff blocks retain source
  line numbers, even when one line wraps across several blocks.
- Questionnaires show one question at a time. Selected option labels,
  descriptions and custom/default answers can be read in full. A final summary
  links back to each question before submission. Mermaid source is shown as text,
  not rendered as a cramped diagram on the watch.
- The editor is height-bounded and uses the system keyboard or speech UI. Dictation
  only updates editable text, never sends it, and does not overwrite intervening
  edits. Speech may use a network service selected by the device; PocketPi never
  stores or relays microphone audio.
- Closing, stopping, unsharing, unpairing, killing jobs, forking and dismissing
  uncertain send records have explicit confirmation screens. Routes are bound to
  identifiers so stale screen actions cannot target a different selected session.

## Supported workflows

| Area | Watch behavior |
| --- | --- |
| Pairing | Independent approval on the Mac, with optional phone-assisted QR scanning |
| Projects | List shared projects and stop sharing a project for all paired devices |
| Sessions | List live, historical and child sessions; create, rename and close |
| Chat | Read messages/tool activity, page history, quote and edit encrypted drafts |
| Running work | Stop pi, Send now or Send when done where pi Remote on the Mac supports it |
| Subagents | Open host-reported child sessions; stop or resume supported children |
| Questions | Confirm, select, free text, plan approval/feedback and questionnaires |
| Configuration | Select a model and thinking level using confirmed host state |
| Advisor | Select model/thinking, disable it or draft a context-sharing request |
| Commands | Select from the host's command catalog; review the draft before dispatch |
| Files | Browse folders, page text, select a line range and quote files/lines into a draft |
| Git | Choose session/HEAD/dev base, read wrapped diffs and add line review comments |
| Forks | Fork a stopped session to edit or explicitly resend a user message |
| Jobs | List supported background jobs, read live output and confirm a stop |
| Mac folders | Browse, create, clone and open; disclose and confirm project trust |
| Send journal | Inspect delivery status and confirm dismissal; never automatically resend |
| Notifications | Optional independent Firebase event notifications with watch routing |
| Status tile | Up to two sessions that need you or are working, with their last-seen pull request checks |
| Complication | Waiting or working count (short text) or the top session with its checks (long text) |
| Phone handoff | Open the matching phone session for photos/documents or a larger view |
| Settings | Thinking visibility, offline-session filtering and watch notification opt-in |

Starting a ticket task stays on the phone and tablet. Choosing a ticket needs its
description, priority and project in view, and the start is a polled operation of up
to ten minutes; that is neither a brief status check nor a one-tap confirmed action
with enough context on a watch display.

Unsupported capabilities stay unavailable. TUI-only prompts may still require
input on the Mac. Advisor context sharing goes through the host's advertised
Advisor command and disclosure question; selecting an Advisor does not grant
context sharing. A context-sharing request does not replace an existing draft.

Photos/documents and camera capture intentionally remain on the phone. Opening
the phone transfers **routing identifiers only**, not watch drafts, attachments,
chat contents or credentials. The phone must already have its own approved pairing
with that Mac. Files selected there belong to the phone's draft and are sent there.

## Status tile

The **PocketPi status** tile, to the right of the watch face, answers "what is the
agent doing" without opening the app. It shows how many sessions need you, when a
paired Mac last confirmed the rows (one time for all paired Macs), and up to two sessions that wait for you or are working,
waiting ones first. Each row names the session and, when the watch has seen one,
its pull request with the checks state and the age of that information, for example
`needs you · PR #189 · 2 failing · 5 min`. A tap opens that session through the same
validated path as a notification; the hint texts open the app.

The rows come from the app's own state while it runs and from push events (a question
marks a session waiting, a completion idle; subagent and job pushes change no row,
and no push changes the rows' time). Because the Mac lists sessions without
their pull requests, the watch remembers the pull request status it last saw for each
session, from an open chat or a tile refresh, and drops it after 24 hours. Every row
shows its age; no push carries pull request data.

Unlike the phone widget, which never asks the network itself, the tile refreshes when
the system asks to draw it and its snapshot is older than 30 seconds. For each paired
Mac the refresh uses the app's connection when it is open and healthy, and otherwise
a short-lived encrypted connection of its own. It reads the project and session lists
(up to 24 projects) and the top session's snapshot for its pull request, and is
bounded to ten seconds per Mac. It never changes the app's selection. Until it ends,
the tile shows the stored rows with their time; a failure leaves them as they were.
The tile sets no update interval, so nothing refreshes on a schedule.

Fresh pull request checks for every row would need the Mac to include a compact pull
request summary in `sessions.list`. That is a protocol change for noah-monorepo first
and is not part of this client.

## Complication

The **PocketPi status** complication puts the tile's answer on the watch face. Short text shows
how many sessions wait for you (`2 waiting`), or, when none wait, how many are working; long
text names the top session with the tile's row detail, for example
`needs you · PR #189 · 2 failing · 5 min`. Unpaired, it reads `Pair`. A tap opens that session
through the tile's validated trampoline, or the app when no session is active.

The complication reads only the tile's stored snapshot and never asks the Mac itself: watch faces
request it far more often than the tile. It sets no update period; every change of the snapshot
(app state, pushes, tile refreshes) asks watch faces to read it again.

## Pairing

1. Install both debug APKs from this branch for phone-assisted pairing. Data Layer
   communication requires the same application ID and signing certificate.
2. On the watch choose **Pair Mac → Scan pairing code on phone**.
3. The phone opens a dedicated watch-pairing screen. Start `/remote` on the Mac,
   scan its fresh QR code and explicitly send the short-lived code to the watch.
4. Return to PocketPi on the watch and approve **the watch's own device key** on
   the Mac. The phone does not pair on its behalf or copy its existing key.

The watch accepts one bounded, valid pairing-code response from the selected phone
node, bound to a random 128-bit nonce. The request expires after five minutes and
is canceled when leaving the pairing screen. QR expiry is enforced by the Mac;
scan a new code if needed. Codes and nonces are memory-only, and no code is logged.
Manual entry of the complete pairing code on the watch remains available when no
phone is connected.

Removing a Mac forgets watch-local credentials, drafts and associated notifications.
Revoke the watch on the Mac as well to invalidate its device key there.

## Persistence and recovery

`WearApplication` owns a watch-local repository, pairing store, draft store and
navigation snapshot store. All persistent conversation material uses the existing
Keystore-backed no-backup storage. Cloud backup and device transfer are disabled.
Android saved state contains only the screen name and host/project/session/detail
identifiers. Question answers, confirmation actions, pairing codes and editor-only
text are not put into saved state.

Restoration uses `ActivationMode.RESTORE`: it does not silently open a historical
copy. An invalid restored session falls back to its project. Reconnect behavior,
prompt acknowledgment journaling and no-automatic-resend rules are shared with
the phone. A close request stays on its original selection until the repository
receives the host result; it is not canceled by an immediate navigation.

Watch-native UI classes live in `wear/src/main/java/de/joinnoah/pi/remote/`. The
module compiles the repository's existing Android source/resource directory as a
single source of truth. Phone entry points are not declared in the watch manifest
and are not used by the watch application. This avoids a broad source-module
migration while adding the new client.

## Independent notifications

Both build variants read the existing `PI_REMOTE_FIREBASE_*` build settings. Their
application ID is the same; each installed Firebase instance obtains its own token.
With absent Firebase settings, push stays disabled and the watch explains why.
With configured settings, enable **Watch notifications** and grant the system
notification permission (API 33+). The relay needs matching Firebase credentials.

Notifications contain only generic event text and opaque routing identifiers.
Question text is read over the encrypted connection after opening the app. The
receiver checks watch-local pairings and opt-in, deduplicates events, suppresses
notices for the foreground session, and does not execute background answers.
Notification navigation validates the saved host through the repository. Returning
from Recents does not replay an old notification target. Local watch notices are
not bridged back to the phone.

Actual FCM delivery, background restrictions, LTE reachability and interaction with
Samsung's phone-notification mirroring are **physical-device acceptance checks**,
not guaranteed by a passing build.

## Build and install

Use JDK 25 and Android SDK platform 37, as for the phone:

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug \
  :wear:testDebugUnitTest :wear:assembleDebug :wear:lintDebug

adb -s PHONE_SERIAL install build/outputs/apk/debug/pocketpi-debug.apk
adb -s WATCH_SERIAL install wear/build/outputs/apk/debug/wear-debug.apk
```

CI includes watch unit/UI tests, debug assembly and Lint alongside the phone checks.
Watch UI tests use a 240dp round Robolectric configuration on API 35. Existing phone
API 36 instrumentation tests remain in CI; no real-watch acceptance is implied.

## Release boundaries

The phone-only release workflow explicitly builds `:bundleRelease`. It is started
manually on `main`, requires successful CI for that commit, and publishes only the
phone artifact to testing tracks under the existing release runbook. This change
does not publish a watch artifact or target the production track.

The watch copies the validated phone upload signing configuration and version name.
A watch release additionally requires `-PPOCKETPI_WEAR_VERSION_CODE=<unused-code>`:
it must be in Play's valid range and distinct from the phone's version code. The
check cannot establish that a code is globally unused on Play; Robin must reserve
one across all artifacts/tracks. `:wear:bundleRelease` depends on the existing
upload-certificate validation and this watch version-code check. Wear OS form-factor
setup, screenshots, review and testing-track uploads are separate release work.

## Manual Play testing release

After merging into `main` and passing its Android CI push run, start **Build
PocketPi Wear testing bundle** in GitHub Actions. It uses the protected
`play-testing` signing/Firebase configuration, checks the registered upload
certificate, and saves `wear-release.aab` as a seven-day workflow artifact. It
never uploads to Play or changes a track. The phone release workflow stays separate.

The Wear build uses the next code observed across Play tracks, APKs and bundles.
Downloading an artifact does not reserve that code: check that it is still unused
in Play Console before upload. Serialize phone and watch releases; both workflows
share the `pocketpi-play-testing` concurrency group. The root phone version code
is set to 1 only as an unbuilt configuration value, distinct from the Wear code.

In the existing `de.robinrehbein.pocketpi` app, add the Wear OS form factor, upload
real Wear screenshots, configure the intended testers, and upload the verified
artifact to the Wear internal testing track. Promote the same artifact to Wear
Closed Alpha. Complete any required Wear review and record the actual delivery
state separately for each track. Physical-device checks above remain outstanding
until performed; a testing release does not establish device acceptance.

## Required Galaxy Watch Ultra checks

- Install matching phone/watch builds and approve the independent watch device.
- Phone-assisted QR transfer: cancellation, expired code, wrong source and retry.
- Cold start and restoration, including historical-session fallback.
- Round-screen clipping, large font scale, TalkBack, keyboard, dictation and Back.
- Device rotary/control behavior: the Ultra has no mechanical rotating bezel.
- Streaming chat, question/plan answers, idle versus busy actions and uncertain sends.
- File paging/selection, diff review, forks, subagent and background-job controls.
- Folder trust disclosures and project/session administration.
- WLAN/LTE transitions, wrist-down/background/foreground recovery and battery use.
- Optional independent push, revoked permission, host revocation and phone mirroring.
- Phone handoff with matching, missing and revoked phone pairings.
- Status tile: add it, round clipping of two rows at large font scale, TalkBack, tap
  routing to a session and to the app, swipe refresh over WLAN and LTE (and its
  battery cost), stale rows after a failed refresh, and push-driven row changes.
- Complication: add short and long text to a Samsung watch face, clipping at large font scale,
  TalkBack, tap routing to a session and to the app, update after a push and after a tile refresh,
  and ambient/always-on display.
- Chat pull request line: appears with an open session that has a pull request.

Do not treat a built APK or a round Robolectric test as evidence that these Samsung
hardware, network, push and power-management behaviors have been accepted.
