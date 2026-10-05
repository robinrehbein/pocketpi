# PocketPi Play testing release

This runbook covers the standalone repository `robinrehbein/pocketpi` and the new Google Play app
`de.robinrehbein.pocketpi`. It does not update the previous Play app `de.joinnoah.pocketpi`.
The target tracks are Internal Testing (`internal`) and the default Closed Alpha track (`alpha`) or one
custom closed track. Production is outside the release workflow.

The first AAB (versionCode 1) is available to internal testers. Closed Alpha versionCode 1 is
also available to its selected testers. No automated release
has been verified. The [release workflow](../../.github/workflows/release.yml) runs after green
`main` CI and publishes to Internal Testing alone while `POCKETPI_CLOSED_TRACK` is unset. Once
that variable is configured, it updates both tracks in one Play edit. Do not use a merge as a
setup test; a green `main` run may publish immediately.

## First-release setup

1. Confirm that [Android CI](../../.github/workflows/ci.yml) passes for the exact `main` commit.
   Require its `PocketPi checks` job before merging. CI checks resource parity, unit tests, the
   debug APK, lint, and API 36 instrumentation tests. It has no Play or release-signing access.
2. Complete the new Play app's store listing, app-content declarations, privacy link, reviewer
   access, countries, and tester lists. Create Internal Testing and Closed Alpha. Use `alpha` for
   the default Closed Alpha track.
   For a custom closed track, obtain its exact identifier from the Play Developer API after the
   first Console upload. The display name and Console URL number are not valid
   `POCKETPI_CLOSED_TRACK` values.
   A video of a paired Mac can support reviewer instructions, but check the Console for any further
   access requirement and review status.
3. Register a separate Firebase Android app with package `de.robinrehbein.pocketpi`. Confirm its
   push credentials and the relay's matching server configuration. The old package's Firebase
   registration cannot supply this app's identity. Prepare `PI_REMOTE_FIREBASE_API_KEY`,
   `PI_REMOTE_FIREBASE_APP_ID`, `PI_REMOTE_FIREBASE_PROJECT_ID`, and
   `PI_REMOTE_FIREBASE_GCM_SENDER_ID` for the protected GitHub environment. Never commit these
   values. The app configures Firebase in code and does not need `google-services.json`.
4. In the Cloud project used for Play publishing, enable the Google Play Android Developer API.
   Create a dedicated service account. Grant it Play Console access limited to
   `de.robinrehbein.pocketpi`, with permissions to view the app and release to testing tracks.
   Do not grant production or financial permissions.
5. Configure GitHub OIDC workload identity federation for that account. Map
   `google.subject=assertion.sub` and restrict the provider to this repository's immutable IDs,
   `main`, and the release environment:

   ```text
   assertion.repository_id=='1391246149' && assertion.repository_owner_id=='4692134' && assertion.ref=='refs/heads/main' && assertion.environment=='play-testing'
   ```

   Grant the GitHub OIDC principal Workload Identity User access on the service account. The
   workflow uses short-lived tokens and needs no downloaded service-account key.
6. Create and protect the GitHub environment `play-testing`. Allow deployments only from `main`.
   Set its OIDC variables now; add `POCKETPI_CLOSED_TRACK` when Alpha is ready for automatic
   updates, using `alpha` for the default track or the exact custom track identifier:

   | Variable | Value |
   | --- | --- |
   | `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full resource name of the PocketPi GitHub OIDC provider. |
   | `GCP_PLAY_SERVICE_ACCOUNT` | Email of the dedicated Play service account. |
   | `POCKETPI_CLOSED_TRACK` | Optional. `alpha` for default Closed Alpha, or the exact custom closed-track ID. Omit for Internal Testing only. |

   Set `POCKETPI_UPLOAD_KEYSTORE_B64`, `POCKETPI_UPLOAD_STORE_PASSWORD`,
   `POCKETPI_UPLOAD_KEY_ALIAS`, `POCKETPI_UPLOAD_KEY_PASSWORD`, and the four
   `PI_REMOTE_FIREBASE_*` values above as environment secrets. Encode the **new app's** upload
   keystore as base64 for `POCKETPI_UPLOAD_KEYSTORE_B64`. Keep its private key and passwords for
   future updates. Do not use the old app's upload key or print secret values in logs.
7. Confirm the new upload certificate SHA-256 fingerprint against Play Console and the
   [Gradle signing gate](../../build.gradle.kts):

   ```text
   E5:0A:EE:1E:63:41:FF:AE:CD:1E:FE:AE:0B:25:B3:64:B5:80:B2:01:1B:E4:3C:06:2F:0A:23:54:1D:72:83:B5
   ```

   `validateUploadKey` checks that the keystore contains an accessible private key and this
   certificate. `verify_bundle.py` checks the signed AAB. For a local release build, pass the
   same values through environment variables or ignored `local.properties` entries. Never commit
   the keystore.
8. Perform the first upload in Play Console. The Android Publisher Edits API cannot modify a new
   app before at least one bundle has been uploaded through the Console. With the new Firebase and
   signing values configured locally, run `./gradlew --no-daemon bundleRelease` and
   `python scripts/release/verify_bundle.py build/outputs/bundle/release/pocketpi-release.aab`.
   Confirm package `de.robinrehbein.pocketpi`, upload certificate, and `versionCode` 1; then upload
   that exact signed AAB to Internal Testing in the new app's Play Console. Complete its release
   and record whether Play has accepted it, is reviewing it, or has made it available. Keep the
   workflow disabled throughout first-upload setup. The first automated release uses the next
   unused version code after this Console upload. This step was completed on 29 September 2026:
   versionCode 1 is available to internal testers.
9. Verify service-account permissions, Firebase configuration, upload key, and that the Publisher
   API can read the app and Internal Testing (`internal`). The workflow is currently active; the next
   successful `main` push CI run may publish to the configured tracks. Before adding Closed Alpha,
   verify its exact API track ID and Play review readiness, then set `POCKETPI_CLOSED_TRACK` in the
   protected environment. If a run fails, inspect Play before retrying. Record the first
   successful Internal and Closed Alpha releases separately from API edit acceptance.

## Normal release after setup

1. Merge a change into `main`, including a documentation change. [Android CI](../../.github/workflows/ci.yml)
   runs on the push. The [release workflow](../../.github/workflows/release.yml) proceeds only
   after that push run succeeds for the current `main` commit. A failed, stale, pull-request, or
   non-`main` run does not publish.
2. The release job authenticates through OIDC and calls
   `python scripts/release/play_release.py prepare`. It reads Play track, bundle, and APK version
   codes and chooses the next integer. The initial Console upload uses `versionCode` 1; the first
   automated release uses at least 2. Gradle receives `POCKETPI_VERSION_CODE`; the visible name uses
   `0.3.19-ci.<code>`. Never reuse a Play version code.
3. The job runs `./gradlew --no-daemon bundleRelease` and builds
   `build/outputs/bundle/release/pocketpi-release.aab`. Gradle requires the four new Firebase
   values and new upload key. `python scripts/release/verify_bundle.py
   build/outputs/bundle/release/pocketpi-release.aab` rejects an unsigned AAB or a certificate
   other than the fingerprint above.
4. `python scripts/release/play_release.py publish --version-code <code> --bundle
   build/outputs/bundle/release/pocketpi-release.aab` rechecks the code, uploads one AAB, and
   updates `internal` in one Play edit. If `POCKETPI_CLOSED_TRACK` is set, the same edit also updates
   that track; every configured track must already exist.
   `ERROR_IF_IN_REVIEW` stops the commit if Play already has an in-progress review. A fresh edit
   then checks that every configured track reports the new code as `completed`.

The workflow has no manual `workflow_dispatch` trigger. Do not rerun a failed release job until
you have checked the actual Play track state. A job can fail after Play accepted the edit; a
rerun can allocate another version.

## Verify delivery

In GitHub Actions, match the successful `Android CI` push run and `Publish PocketPi to Play
testing` run to the same current `main` commit. The publish step should report
`Committed versionCode <code> to qa` or `Committed versionCode <code> to internal and <closed-track-id>`.

In Play Console, inspect each configured track for package `de.robinrehbein.pocketpi`, the version
code, and its review and availability state. A
successful API commit does not establish tester availability. Once Play makes the release
available, install through each track's opt-in link. This new package installs separately from
`de.joinnoah.pocketpi`; pair it with the Mac again. Pairing credentials, drafts, and settings do
not transfer. Check a session and push delivery with a real Mac and Android device. Record the
commit, GitHub run URLs, version code, Play status, and device result in the release ticket.

Read-only commands from the repository root:

```sh
gh run list --repo robinrehbein/pocketpi --workflow 'Android CI' --branch main --limit 5
gh run list --repo robinrehbein/pocketpi --workflow 'Publish PocketPi to Play testing' --branch main --limit 5
gh run view <run-id> --repo robinrehbein/pocketpi --log-failed
```

## Failure and recovery

- If CI fails, inspect its failed step and the uploaded `pocketpi-verification` reports. Fix the
  source in a pull request. The release workflow must not publish that commit.
- If OIDC, Play permissions, the tracks, Firebase values, or signing fail, fix the named
  prerequisite. The upload key and Firebase registration must belong to the new package.
  `validateUploadKey` and `verify_bundle.py` must pass. Do not replace the registered key to
  bypass a failure.
- If Play reports a review in progress, wait or resolve it in Play Console. The release script
  fails with `ERROR_IF_IN_REVIEW` so it does not cancel the existing review.
- If upload or commit fails, inspect both tracks before retrying. The script deletes an
  uncommitted edit; it cannot undo a committed release. A failure during post-commit verification
  may mean that both tracks already contain the code.
- If testers receive a bad release, stop further merges and halt the affected testing release in
  Play Console where that control exists. Fix the app, pass CI, and publish a higher version code
  to both tracks. Play does not accept a downgrade to an older AAB with a lower code. Do not use
  production as a recovery path.

The relevant implementation is in [the release workflow](../../.github/workflows/release.yml),
[Gradle](../../build.gradle.kts), [the Play release script](../../scripts/release/play_release.py),
and [the bundle verifier](../../scripts/release/verify_bundle.py).
