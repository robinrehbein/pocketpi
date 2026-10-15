#!/usr/bin/env bash
# Quick pre-push check: resource parity, then one Gradle run that compiles every source set a
# change can break and runs the unit tests related to the change. Lint, the full unit suite and
# the emulator tests run in CI (.github/workflows/ci.yml), which must be green before a merge.
#
# Usage: scripts/quick-check.sh [--full]
#   --full                    run the full local set from CLAUDE.md instead
#   QUICK_CHECK_LOW_MEMORY=1  smaller Gradle heap and one worker, for 15 GB cloud boxes
set -euo pipefail

cd "$(dirname "$0")/.."

# Cloud sessions keep JAVA_HOME, ANDROID_HOME and the Robolectric mirror here.
if [[ -f /opt/pocketpi-env.sh ]]; then
    # shellcheck disable=SC1091
    source /opt/pocketpi-env.sh
fi

full=false
case "${1:-}" in
    "") ;;
    --full) full=true ;;
    *) echo "Usage: $0 [--full]" >&2; exit 2 ;;
esac

started=$SECONDS
finish() {
    local status=$?
    if [[ $status -eq 0 ]]; then
        echo "quick-check: OK ($((SECONDS - started))s)"
    else
        echo "quick-check: FAIL ($((SECONDS - started))s)"
    fi
}
trap finish EXIT

gradle_args=()
if [[ "${QUICK_CHECK_LOW_MEMORY:-}" == "1" ]]; then
    gradle_args+=("-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
        "-Dorg.gradle.workers.max=1")
    echo "quick-check: low-memory Gradle settings"
fi

echo "quick-check: English and German resource parity"
python3 scripts/i18n-check.py .

if $full; then
    echo "quick-check: --full, running the full local set"
    ./gradlew "${gradle_args[@]}" \
        testDebugUnitTest assembleDebug lintDebug \
        :wear:testDebugUnitTest :wear:assembleDebug :wear:lintDebug \
        assembleDebugAndroidTest :assembleBenchmark :benchmarks:assembleBenchmark
    exit 0
fi

# Changed files against the merge base with origin/main: commits, staged and unstaged edits
# (one diff against the working tree) plus untracked files.
base="$(git merge-base HEAD origin/main)"
mapfile -t changed < <(
    { git diff --name-only "$base"; git ls-files --others --exclude-standard; } | sort -u
)

code=()
for path in "${changed[@]}"; do
    case "$path" in
        *.md | docs/*) ;;
        *) code+=("$path") ;;
    esac
done
if [[ ${#code[@]} -eq 0 ]]; then
    echo "quick-check: no code changes (${#changed[@]} doc files changed), skipping Gradle"
    exit 0
fi
echo "quick-check: ${#code[@]} changed non-doc files"

# A build, dependency or manifest change can affect any test, so it runs the whole suite.
whole_suite=""
for path in "${code[@]}"; do
    case "$path" in
        *.gradle.kts | gradle/* | gradle.properties | gradlew | */AndroidManifest.xml | AndroidManifest.xml)
            whole_suite="$path changed"
            break
            ;;
    esac
done

# Related unit tests: changed test classes, plus test classes whose source names a changed Kotlin
# file (ChatComposer.kt -> tests mentioning ChatComposer) or test resource (files-v1.json). A
# changed test file without @Test (a fake or helper) counts as a changed name, not a test class.
tests=()
wear_tests=false
benchmark_tests=false
add_test() { grep -q '@Test' "$1" && tests+=("$(basename "$1" .kt)"); return 0; }
for path in "${code[@]}"; do
    case "$path" in
        wear/*) wear_tests=true ;;
        src/benchmark/* | src/testBenchmark/*) benchmark_tests=true ;;
    esac
    name=""
    case "$path" in
        src/test/java/*.kt)
            [[ -f "$path" ]] && add_test "$path"
            name="$(basename "$path" .kt)"
            ;;
        src/test/resources/*) name="$(basename "$path")" ;;
        *.kt | *.java) name="$(basename "${path%.*}")" ;;
    esac
    [[ -n "$name" ]] || continue
    while IFS= read -r test_file; do
        add_test "$test_file"
    done < <(grep -rlwF --include='*.kt' -- "$name" src/test/java || true)
    # The wear module compiles the app's main sources, so its tests can depend on them too.
    if grep -rqwF --include='*.kt' -- "$name" wear/src/test; then
        wear_tests=true
    fi
done
mapfile -t tests < <(printf '%s\n' "${tests[@]+"${tests[@]}"}" | sed '/^$/d' | sort -u)
if [[ -z "$whole_suite" && ${#tests[@]} -gt 40 ]]; then
    whole_suite="${#tests[@]} related test classes"
fi

# Compiling these covers every source set a change can break: app main and unit tests, the
# androidTest fakes, the app's benchmark variant (src/benchmark, src/testBenchmark), the benchmarks
# module and the wear module, which compiles every file in src/main/java.
tasks=(
    :compileDebugUnitTestKotlin
    :compileDebugAndroidTestKotlin
    :compileBenchmarkUnitTestKotlin
    :benchmarks:compileBenchmarkKotlin
    :wear:compileDebugUnitTestKotlin
)
if [[ -n "$whole_suite" ]]; then
    echo "quick-check: whole :testDebugUnitTest ($whole_suite)"
    tasks+=(:testDebugUnitTest)
elif [[ ${#tests[@]} -gt 0 ]]; then
    echo "quick-check: ${#tests[@]} related test classes: ${tests[*]}"
    tasks+=(:testDebugUnitTest)
    for test in "${tests[@]}"; do
        tasks+=(--tests "*.$test")
    done
else
    echo "quick-check: no related unit tests, compiling only"
fi
if $wear_tests; then
    echo "quick-check: wear sources or wear tests affected, running :wear:testDebugUnitTest"
    tasks+=(:wear:testDebugUnitTest)
fi
if $benchmark_tests; then
    echo "quick-check: benchmark sources changed, running :testBenchmarkUnitTest"
    tasks+=(:testBenchmarkUnitTest)
fi

./gradlew "${gradle_args[@]}" "${tasks[@]}"
