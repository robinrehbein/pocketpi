# PocketPi Play testing release

PocketPi uses the existing Google Play app `de.joinnoah.pocketpi`. This runbook covers the
standalone repository `robinrehbein/pocketpi`, Play Internal Testing (`qa`), and one configured
custom Closed Alpha track. The release workflow does not update production.

## First release setup

1. Merge the standalone CI and release workflows into `main`. In GitHub, require the `PocketPi
   checks` job from [Android CI](../../.github/workflows/ci.yml) before merging. CI runs resource
   parity, unit tests, a debug build, lint, and API 36 instrumentation tests.
2. In the Google Cloud project that owns Play API access, enable the Google Play Android Developer
   API. Create a dedicated service account for PocketPi publishing. In Play Console, invite that
   account with access limited to this app and the permissions needed to view the app and release to
   testing tracks. Do not grant production or financial permissions.
3. Configure GitHub OIDC workload identity federation for that service account. Map
   `google.subject=assertion.sub` and restrict the provider to the repository's immutable IDs,
   `main`, and the release environment:

   ```text
   assertion.repository_id=='1391246149' && assertion.repository_owner_id=='4692134' && assertion.ref=='refs/heads/main' && assertion.environment=='play-testing'
   ```

   Grant the GitHub OIDC principal the Workload Identity User role on that service account. The
   workflow uses short-lived access tokens and does not need a downloaded service account key.
4. Create and protect the GitHub environment `play-testing`. Allow deployment only from `main`.
   Set these environment variables:

   | Variable | Value |
   | --- | --- |
   | `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full provider resource name for the PocketPi GitHub OIDC provider. |
   | `GCP_PLAY_SERVICE_ACCOUNT` | Email address of the dedicated Play service account. |
   | `POCKETPI_CLOSED_TRACK` | Exact custom track identifier returned by the Play Developer API. It is not the Play Console display name or URL number. |

   Set these environment secrets: `POCKETPI_UPLOAD_KEYSTORE_B64`,
   `POCKETPI_UPLOAD_STORE_PASSWORD`, `POCKETPI_UPLOAD_KEY_ALIAS`,
   `POCKETPI_UPLOAD_KEY_PASSWORD`, `PI_REMOTE_FIREBASE_API_KEY`,
   `PI_REMOTE_FIREBASE_APP_ID`, `PI_REMOTE_FIREBASE_PROJECT_ID`, and
   `PI_REMOTE_FIREBASE_GCM_SENDER_ID`. The keystore secret is the base64 encoding of the
   **existing** PocketPi upload keystore. Never commit the keystore, its passwords, or Firebase
   values. Do not create a replacement signing key for an app update.
5. In Play Console, confirm that the custom Closed Alpha track exists and has its testers,
   countries, app-content declarations, and reviewer access ready. The first closed release may
   require Play review. A video showing a paired Mac can support the reviewer instructions, but
   its availability and Play's acceptance must be checked in the Console.

Before enabling the first automatic release, resolve the current setup gaps: locate the existing
upload keystore; finish OIDC, the protected environment and its secrets; grant the dedicated service
account app-scoped Play permissions; obtain the exact closed track ID; finish the Closed Alpha
reviewer-access material and outstanding Play declarations. Internal Testing currently has
`0.3.19` (`versionCode` 22), while Closed Alpha has a draft rather than a live release. Recheck
both states in Play Console before using this checklist. No automatic dual-track publication has
been verified yet.

## Normal release

1. Merge a PocketPi change into `main`. The [Android CI workflow](../../.github/workflows/ci.yml)
   runs on the push. The [release workflow](../../.github/workflows/release.yml) starts only after
   that CI run succeeds for the **current** `main` commit. A failed, stale, pull-request, or
   non-`main` run does not publish.
2. The release job checks the protected environment, authenticates through OIDC, and calls
   `python scripts/release/play_release.py prepare`. The script reads Play track, bundle, and APK
   version codes and chooses the next integer. Gradle receives it as `POCKETPI_VERSION_CODE` and
   receives a visible name of the form `0.3.19-ci.<code>`. Do not reuse a version code.
3. The job builds `build/outputs/bundle/release/pocketpi-release.aab` with `./gradlew --no-daemon
   bundleRelease`. Gradle's `validateReleasePushConfiguration` and `validateUploadKey` tasks require
   the Firebase values and the existing private upload key. The upload certificate must have
   SHA-256 fingerprint
   `06:0C:E8:05:BB:E7:36:AF:A7:30:F3:DF:F3:05:01:2A:62:2A:88:6A:EE:8E:E1:95:47:2D:87:1B:C9:B7:04:15`.
   `python scripts/release/verify_bundle.py build/outputs/bundle/release/pocketpi-release.aab`
   checks that the AAB is fully signed by that certificate.
4. `python scripts/release/play_release.py publish --version-code <code> --bundle
   build/outputs/bundle/release/pocketpi-release.aab` rechecks the code, uploads **one** AAB, and
   updates `qa` and `POCKETPI_CLOSED_TRACK` in one Play edit. It uses
   `ERROR_IF_IN_REVIEW`, so an existing Play review blocks the commit instead of being canceled.
   The script then opens a fresh edit to verify that both tracks report the same completed code.

The workflow has no `workflow_dispatch` trigger. Do not rerun a failed release job without reading
its failure and the actual Play track state. A job can fail after Play accepted the edit, and a
rerun would allocate and upload another version.

## Verify delivery

In GitHub Actions, open the successful `Android CI` push run and the corresponding `Publish
PocketPi to Play testing` run. The release must name the same `main` commit. The last step should
report `Committed versionCode <code> to qa and <closed-track-id>`.

In Play Console, inspect both Internal Testing and Closed Alpha for the same package, version code,
and release status. Check whether Google has put the change in review. A successful API commit does
not prove the update is available to testers. Install through each track's opt-in link when Play
reports availability, then check pairing, a session, and push notification delivery with a real
Mac and Android device. Record the commit, GitHub run URLs, version code, Play status, and device
result in the release ticket.

Read-only command examples from the repository root:

```sh
gh run list --repo robinrehbein/pocketpi --workflow 'Android CI' --branch main --limit 5
gh run list --repo robinrehbein/pocketpi --workflow 'Publish PocketPi to Play testing' --branch main --limit 5
gh run view <run-id> --repo robinrehbein/pocketpi --log-failed
```

## Failure and recovery

- If CI fails, inspect its failed step and the uploaded `pocketpi-verification` reports. Fix the
  source on a new pull request. The release workflow must not run for that commit.
- If configuration, OIDC, Play permissions, the closed track, Firebase values, or signing fails,
  fix the named prerequisite in its owner system. `validateUploadKey` and `verify_bundle.py` must
  pass with the existing key. Never print a secret or replace the key to bypass a failure.
- If Play says a review is in progress, wait for the review or resolve it in Play Console. The
  release script deliberately fails with `ERROR_IF_IN_REVIEW`.
- If upload or commit fails, inspect both tracks before retrying. The script deletes an uncommitted
  edit; it cannot undo a committed release. If the job fails during post-commit verification, first
  determine whether both tracks already contain the code.
- If testers receive a bad release, stop further merges and use Play Console to halt the affected
  testing release where that control is available. Fix the app, pass CI, and publish a higher
  `versionCode` to both tracks. Play does not accept a downgrade to the older AAB at a lower code.
  Do not switch this workflow to production as a recovery step.

The code for the exact gates and Play operations is in
[`.github/workflows/release.yml`](../../.github/workflows/release.yml),
[`build.gradle.kts`](../../build.gradle.kts),
[`scripts/release/play_release.py`](../../scripts/release/play_release.py), and
[`scripts/release/verify_bundle.py`](../../scripts/release/verify_bundle.py).
