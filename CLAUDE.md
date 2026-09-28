# PocketPi

PocketPi is the Android client for pi Remote. It pairs with a Mac running pi, lists the Mac's
shared projects and sessions, and chats with them through the pi Remote relay. The app ID is
`de.robinrehbein.pocketpi`; the Kotlin package is still `de.joinnoah.pi.remote`.

The pi host, relay, extensions and wire protocol live in `robinrehbein/noah-monorepo`
(`packages/pi-remote`). This repository holds only the Android app and copies of the protocol test
fixtures in `src/test/resources` (the README names their source commit). A change that needs a new
protocol field or host behaviour lands in noah-monorepo first, under that repository's rules.

## Working here

Robin is the founder and the only developer. Answer him in German in chat; write code, docs,
commits and PRs in English.

This repository does not use noah-monorepo's process: no Plane tickets, risk tiers, plan sign-off
or `review-diff.ts`. The process is:

1. One branch and one pull request per change, against `main`.
2. Run the checks below locally before pushing.
3. Get one independent review of the diff (a reviewer subagent is enough) and fix what it finds.
4. CI must be green on the PR's head.
5. Merge after Robin agrees, as a merge commit.

## Checks

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
```

CI also runs the emulator tests (`./gradlew pixel2Api36DebugAndroidTest`), which need `/dev/kvm`.
Every user-facing string exists in `src/main/res/values/strings.xml` (English) and
`src/main/res/values-de/strings.xml` (German).

Device checks (real phones, Samsung quirks, push delivery, Play uploads) are Robin's. Say which
ones a change needs instead of claiming them.

## Releases

`.github/workflows/release.yml` publishes to Play testing tracks after a merge to `main`. It stays
disabled until the setup in `docs/runbooks/play-testing-release.md` is complete. Never target the
old `de.joinnoah.pocketpi` app or the production track.
