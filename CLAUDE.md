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
2. Run `scripts/quick-check.sh` before pushing (see Checks).
3. Get one independent review of the diff (a reviewer subagent is enough) and fix what it finds.
4. CI must be green on the PR's head.
5. Merge after Robin agrees, as a merge commit.

## Checks

Before pushing, run `scripts/quick-check.sh`. It checks resource parity, then in one Gradle run
compiles app main, unit tests, androidTest, the benchmark variant, the benchmarks module and wear,
and runs the unit tests related to the changed files: tests named after a changed file or naming
it or what it declares. A build file, Gradle setting, manifest or app resource change, or more than
40 related classes, runs the whole suite; wear and benchmark changes add their own tests. It skips
Gradle when only docs changed. `QUICK_CHECK_LOW_MEMORY=1` fits it on a 15 GB cloud box.

Lint, R8, the full unit suite and the emulator tests run only in CI, which must be green before a
merge. CI runs the set below plus the emulator tests (`./gradlew pixel2Api36DebugAndroidTest`,
which need `/dev/kvm`). `scripts/quick-check.sh --full` runs the set locally, and
`./gradlew lintDebug :wear:lintDebug` reproduces a lint failure.

```sh
python3 scripts/i18n-check.py .
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew :wear:testDebugUnitTest :wear:assembleDebug :wear:lintDebug
./gradlew assembleDebugAndroidTest :assembleBenchmark :benchmarks:assembleBenchmark
```

The wear module compiles every source file in `src/main/java`, and the androidTest and benchmark
source sets hold fakes of app interfaces, so a change can pass the app's own build and still break
one of the others; that is why the quick check compiles them all. Lint reports only findings
missing from `lint-baseline.xml` and `wear/lint-baseline.xml`; fix new findings instead of
regenerating a baseline.
Every user-facing string exists in `src/main/res/values/strings.xml` (English) and
`src/main/res/values-de/strings.xml` (German).

Device checks (real phones, Samsung quirks, push delivery, Play uploads) are Robin's. Say which
ones a change needs instead of claiming them.

## Releases

`.github/workflows/release.yml` publishes to Play testing tracks when Robin starts it by hand
(`workflow_dispatch` on `main`); a merge alone does not publish. It refuses to run unless the Android
CI push run for the current `main` commit succeeded. Setup and recovery are in
`docs/runbooks/play-testing-release.md`. Never target the old `de.joinnoah.pocketpi` app or the
production track.
