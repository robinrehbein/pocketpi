# PocketPi for Android

This separate application controls pi sessions on paired Macs through the pi Remote relay. It uses
application ID `de.joinnoah.pocketpi` and does not share Noah accounts or data.

## Build and install

From the repository root, with JDK 25 and the Android SDK configured:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
adb install build/outputs/apk/debug/pocketpi-debug.apk
```

## Continuous integration

[`Android CI`](.github/workflows/ci.yml) checks the exact head commit of each pull request
into `main` and each commit pushed to `main`. The workflow uses JDK 25, Android SDK platform
37 and build tools 37.0.0. It checks English and German resource keys, runs unit tests, builds
the debug APK, runs Android Lint, and runs the managed API 36 emulator instrumentation tests.
GitHub-hosted Linux must provide `/dev/kvm` for the emulator step. On failure, the workflow
uploads available Gradle reports and test results as the `pocketpi-verification` artifact.

Run the resource check locally with `python3 scripts/i18n-check.py .`. The checker uses only
Python's standard library and reports keys missing from either locale. CI builds do not use
release signing keys, Play credentials or Firebase release configuration.

Start `/remote` in pi on the Mac, scan the QR code and approve the device on the Mac. You can also
paste the complete pairing code. The code expires after two minutes. Projects must be shared by the
Mac before they appear in the app. Historical sessions continue as copies through the host.

Swipe left on a project row to reveal the unshare action. Confirming stops sharing only that
project from the Mac for all paired devices. It leaves the project files and other shared projects
in place. The action requires a connected host that supports project unsharing.

Pairings are encrypted with an Android Keystore key in the application's no-backup directory. The
manifest disables cloud backup and device transfer. Removing a pairing deletes its local credentials.
Use the Mac's device revocation command to invalidate the device key at the host too.

## Conversations

The session list shows each conversation's title, available preview and last update. A green dot
means the session is ready; a gray dot means it is offline or requires opening a historical copy.
Subagent sessions appear below their parent and keep their own status, preview and controls.
The floating plus button starts a conversation in the selected shared project.

Swipe a message to the left, or tap its quote icon, to reference it in your reply. The
composer shows the selected excerpt and lets you remove it. Quotes belong to the current session's
draft and survive app restarts. Sending includes the excerpt in ordinary prompt text, so pi and the
saved history retain the reference. It does not grant access to another conversation or branch.
Messages with a timestamp from pi show the device's local date and time. Older messages without a
valid timestamp show no time.

## Dictation

When the draft is empty, tap the microphone to dictate a short message. PocketPi puts the final
transcription in the editable draft and never sends it automatically. It prefers Android's on-device
recognizer when available. A system recognizer may use a network connection, and the composer shows
that before recording starts.

PocketPi does not store or relay microphone audio. Canceling dictation, leaving the conversation, or
putting the app in the background stops recognition. Typing while recognition is active keeps the
keyboard draft and discards the later transcription. Test the selected recognizer on a physical device
before relying on it, because Android's recognition services vary by device.

Assistant messages show their recorded model identity when available. Tool calls and results appear
as expandable activity cards. The **Show thinking** switch in Settings is off by default and shows
only the thinking status. Turn it on to see thinking text when the host streams it. Hidden or
redacted provider reasoning is not exposed.

Model and thinking controls read the selected session's actual configuration. A change applies only
to that session while it is idle. The model picker lists the models available to pi on the Mac;
credentials stay there. The app waits for the confirmed configuration before showing a change.

The Advisor pill shows the selected session's Advisor, activity and remaining consultations. Tap it
to choose an authenticated model and thinking level or turn the Advisor off for that session.
Context sharing is offered separately. Selecting it opens the Advisor's disclosure and requires
confirmation before the active conversation can be sent to the second model. The permission stays
in the Mac runtime and is revoked when the Advisor or session changes.

Type `/` to find the selected session's advertised extension commands, prompt templates and skills.
Choosing an entry inserts its name and preserves arguments. Commands absent from that catalog are
not executed remotely. Remove a quote before sending a slash command. A dispatch acknowledgment
means pi received the command; it does not mean the command finished. Some terminal dialogs still
require input on the Mac.

These controls require an updated host and remote extension. Restart the host after installing an
update, and run `/reload` in terminal sessions. Older hosts can still provide basic conversations;
unsupported controls remain unavailable.

## Photos and files

Use the composer attachment action to select photos or documents with Android's system pickers.
PocketPi copies the selection into encrypted no-backup storage immediately; it does not need broad
storage access or a permanent URI grant. Photos are rotated from EXIF metadata, resized to at most
2048 pixels and encoded as JPEG of at most 1 MiB. Documents retain their bytes.

A prompt accepts up to five attachments and 40 MiB total, with a 20 MiB limit per file. Photos have a
combined 2 MiB limit. Image input requires a model that supports it. Remove attachments before
sending slash commands.

Uploads use the existing encrypted connection in bounded chunks. The Mac stores files in a private
attachment directory and checks their digest and session ownership before sending the prompt.
Images become native image input; other files appear as local paths in the prompt so pi can read
them. The conversation shows attachment metadata, not embedded file contents.

Unsubmitted uploads expire after one hour. Submitted files remain on the Mac for seven days. A
saved conversation retains the reference after expiry, but the temporary file may no longer exist.
The phone keeps local copies while an encrypted draft or an uncertain send still references them.
Disconnecting does not automatically resubmit a prompt.

## Firebase

Push remains disabled when Firebase settings are absent. Configure a separate Firebase Android app
for `de.joinnoah.pocketpi`. Supply these values through environment variables, Gradle properties or
`local.properties`:

- `PI_REMOTE_FIREBASE_API_KEY`
- `PI_REMOTE_FIREBASE_APP_ID`
- `PI_REMOTE_FIREBASE_PROJECT_ID`
- `PI_REMOTE_FIREBASE_GCM_SENDER_ID`

Each linked worktree needs its own ignored `local.properties` or environment values.
Before a release, run `./gradlew validateReleasePushConfiguration` in that worktree. `bundleRelease`
and `assembleRelease` run the same check and stop if a required value is missing. Do not copy
these values into tracked files or print them in release logs.

The app initializes Firebase programmatically and does not require `google-services.json`. The relay
needs the matching server credentials. Enable notifications after pairing. Declining permission does
not disable remote control. Notifications contain generic completion or question text and opaque
routing identifiers. The app only opens notification targets associated with a saved host.

## Behavior and checks

Drafts and unresolved prompt acknowledgments are encrypted with Android Keystore in the no-backup
directory, separately from pairing credentials. Writes are serialized, and a prompt is sent only
after its unresolved acknowledgment marker has been saved. An acknowledgment clears the submitted
text only if the user has not edited it since sending. Removing a host also removes its drafts.

A reconnect validates the selected project and session and reads a fresh snapshot. PocketPi checks
again when the app returns to the foreground on a validated network, and after the default network
changes. It keeps the socket while the app is in the background and stops automatic retries there.
It never opens or forks a historical session, and never retries prompts or answers automatically.
After an uncertain prompt result, read the conversation before sending the draft again. In a
running host-owned RPC session, Queue follow-up submits the draft to pi's follow-up queue; the
journal distinguishes queued messages from uncertain outcomes. Stop clears the RPC queue before
aborting, but pi does not return request IDs for removed messages, so PocketPi cannot claim that a
particular follow-up was discarded. The journal keeps up to 64 entries per session; dismiss an
uncertain entry after checking the conversation to make room without resending it. Ordinary prompts
and slash commands remain idle-only. Interactive Mac TUI sessions do not accept remote follow-up
while busy; the information icon next to the chat title explains their local controls. Refresh
reconnects an offline saved host. The Mac must remain awake with the host service running.

The interface follows the system language with English fallback and German resources. Appearance can
follow the system or use the illustrated light or dark choices. QR recognition runs locally with
CameraX and ZXing.

The app uses Compose BOM `2026.09.00`, Material 3 `1.4.0`, and Navigation 3 `1.1.7`.
`rememberNavBackStack` saves typed host, project and session identifiers. `NavDisplay` owns the
transitions and predictive back handling. Each destination has its own ViewModel; an
Application-scoped repository owns the connection, protocol state and persistence. Screens collect
state with lifecycle awareness. Chat text, credentials and drafts do not enter Android saved state.

Instrumentation tests run against the production navigation with a fake host connection. They cover
back navigation, restored state and notification routing. Separate device tests exercise Android
Keystore and encrypted file recreation. With an attached emulator, run
`./gradlew connectedDebugAndroidTest`. For the managed API 36 device used by CI, run
`./gradlew pixel2Api36DebugAndroidTest`.

Unit tests consume the five JSON fixtures in `src/test/resources/`. These are byte-for-byte copies
of `packages/pi-remote/protocol/fixtures/` from noah-monorepo commit
`5e7b48b1fec80fec1625fd9067edbee205f7b1df`. When the upstream protocol fixtures change,
copy the changed files into `src/test/resources/`, compare their bytes with the upstream source,
and run `./gradlew testDebugUnitTest`. The fixtures cover byte-identical
HKDF, AES-GCM and QR encoding, replay and tampering rejection, session request correlation, history
pagination, early events and questionnaire defaults. A built APK does not establish Samsung device,
TalkBack, mobile-network or real Firebase delivery acceptance; those require the configured host,
relay and device.

## Appearance

PocketPi uses the official pi mark for its adaptive launcher icon and a monochrome silhouette for
notifications. The logo attribution is included in `NOTICE` and in the APK assets.

UI typography requests Samsung's installed `sec` font family, then the older
`sec-roboto-light` family, through Compose's optional local font resolver. It retains the weights
and sizes of the typography styles. Code blocks use monospace. No Samsung font files are bundled:
on devices without those families, including the AOSP emulator, Android supplies its default font.
The actual Samsung font therefore requires verification on a Samsung device.

The installed Android application ID is `de.joinnoah.pocketpi`. This repository is PocketPi's
Gradle root. The internal Kotlin namespace, pi extension directory and `/remote` command
retain their existing names.
A previous debug installation under `de.joinnoah.pi.remote` is a separate app and does not share
pairings or settings with PocketPi.
