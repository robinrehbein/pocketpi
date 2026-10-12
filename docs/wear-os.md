# PocketPi for Wear OS

The `wear` application is an independent pi Remote client for Wear OS 3 and newer
(API 30+), designed for the round display of the Galaxy Watch Ultra. It connects
through WLAN, LTE or the watch's system-provided network. The Mac must remain awake.

Phone and watch share application ID `de.robinrehbein.pocketpi`, but have **separate
installations, device keys, pairings and drafts**. The watch uses the existing
Android protocol, encrypted transport and repository implementation. No host or
relay protocol changes are required.

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
| Running work | Stop, steer or queue follow-up where the host advertises support |
| Subagents | Open host-reported child sessions; stop or resume supported children |
| Questions | Confirm, select, free text, plan approval/feedback and questionnaires |
| Configuration | Select a model and thinking level using confirmed host state |
| Advisor | Select model/thinking, disable it or draft a context-sharing request |
| Commands | Select from the host's command catalog; review the draft before dispatch |
| Files | Browse folders, page text, select a line range and quote files/lines into a draft |
| Git | Choose session/HEAD/dev base, read wrapped diffs and add line review comments |
| Branches | Fork a stopped conversation to edit or explicitly resend a user message |
| Jobs | List supported background jobs, read live output and confirm a stop |
| Mac folders | Browse, create, clone and open; disclose and confirm project trust |
| Send journal | Inspect delivery status and confirm dismissal; never automatically resend |
| Notifications | Optional independent Firebase event notifications with watch routing |
| Phone handoff | Open the matching phone session for photos/documents or a larger view |
| Settings | Thinking visibility, offline-session filtering and watch notification opt-in |

Unsupported capabilities stay unavailable. TUI-only prompts may still require
input on the Mac. Advisor context sharing goes through the host's advertised
Advisor command and disclosure question; selecting an Advisor does not grant
context sharing. A context-sharing request does not replace an existing draft.

Photos/documents and camera capture intentionally remain on the phone. Opening
the phone transfers **routing identifiers only**, not watch drafts, attachments,
chat contents or credentials. The phone must already have its own approved pairing
with that Mac. Files selected there belong to the phone's draft and are sent there.

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

The phone-only release workflow explicitly builds `:bundleRelease`. It does not
publish a watch artifact and remains disabled under the existing release runbook.
There is no production-track upload in this change.

The watch copies the validated phone upload signing configuration and version name.
A watch release additionally requires `-PPOCKETPI_WEAR_VERSION_CODE=<unused-code>`:
it must be in Play's valid range and distinct from the phone's version code. The
check cannot establish that a code is globally unused on Play; Robin must reserve
one across all artifacts/tracks. `:wear:bundleRelease` depends on the existing
upload-certificate validation and this watch version-code check. Wear OS form-factor
setup, screenshots, review and testing-track uploads are separate release work.

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

Do not treat a built APK or a round Robolectric test as evidence that these Samsung
hardware, network, push and power-management behaviors have been accepted.
