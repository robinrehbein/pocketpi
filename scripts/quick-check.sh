#!/usr/bin/env bash
# Quick pre-push check: resource parity, then one Gradle run that compiles every source set a
# change can break and runs the unit tests related to the change. Lint, R8, the full unit suite
# and the emulator tests run in CI (.github/workflows/ci.yml), which must be green before a merge.
#
# Usage: scripts/quick-check.sh [--full]
#   --full                    run the full local set from CLAUDE.md instead
#   QUICK_CHECK_LOW_MEMORY=1  smaller Gradle heap and one worker, for 15 GB cloud boxes
#
# Written for bash 3.2 too (macOS): no mapfile, and possibly empty arrays expand as
# ${array[@]+"${array[@]}"} so set -u accepts them.
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
    local status="${1:-$?}"
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
        "-Dorg.gradle.workers.max=1" "--max-workers=1")
    echo "quick-check: low-memory Gradle settings"
    # A daemon started with the project's 4 GB heap is not reused with these settings but keeps
    # its memory.
    if pgrep -f '[G]radleDaemon' >/dev/null 2>&1; then
        echo "quick-check: a Gradle daemon is already running; './gradlew --stop' frees its memory"
    fi
fi

echo "quick-check: English and German resource parity"
python3 scripts/i18n-check.py .

if $full; then
    echo "quick-check: --full, running the full local set"
    ./gradlew ${gradle_args[@]+"${gradle_args[@]}"} \
        testDebugUnitTest assembleDebug lintDebug \
        :wear:testDebugUnitTest :wear:assembleDebug :wear:lintDebug \
        assembleDebugAndroidTest :assembleBenchmark :benchmarks:assembleBenchmark
    exit 0
fi

# The base is the merge base with origin/main, or with a local main when there is no remote one.
base_ref=""
for ref in origin/main main; do
    if git rev-parse --verify --quiet "$ref^{commit}" >/dev/null; then
        base_ref="$ref"
        break
    fi
done
if [[ -z "$base_ref" ]]; then
    echo "quick-check: neither origin/main nor main exists; run 'git fetch origin main'" >&2
    exit 1
fi
base="$(git merge-base HEAD "$base_ref")"

# Changed files: commits, staged and unstaged edits (one diff against the working tree), deleted
# files under their old names, and untracked files.
changed=()
while IFS= read -r path; do
    [[ -n "$path" ]] && changed+=("$path")
done < <(
    {
        git -c core.quotePath=false diff --name-only --no-renames "$base"
        git -c core.quotePath=false ls-files --others --exclude-standard
    } | sort -u
)
if [[ ${#changed[@]} -eq 0 ]]; then
    echo "quick-check: no changes against $base_ref, nothing to check"
    exit 0
fi

code=()
for path in "${changed[@]}"; do
    case "$path" in
        *.md | docs/*) ;;
        *) code+=("$path") ;;
    esac
done
if [[ ${#code[@]} -eq 0 ]]; then
    echo "quick-check: no non-doc changes against $base_ref (doc files changed: ${#changed[@]}), skipping Gradle"
    exit 0
fi
echo "quick-check: ${#code[@]} changed non-doc files against $base_ref"

# A build, dependency, manifest or app resource change can affect any test, so it runs the
# whole suite.
whole_suite=""
for path in "${code[@]}"; do
    case "$path" in
        *.gradle.kts | gradle/* | gradle.properties | gradlew | *AndroidManifest.xml | src/main/res/*)
            whole_suite="$path changed"
            break
            ;;
    esac
done

# Top-level class, object, interface and function names a Kotlin file declares, at most 30.
# Private declarations are left out (tests cannot use them), and so are names shorter than four
# characters, which match too many unrelated tests.
declared_names() {
    grep -v '^private[[:space:]]' "$1" | sed -nE \
        -e 's/^([a-z]+[[:space:]]+)*(class|object|interface|typealias)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\3/p' \
        -e 's/^([a-z]+[[:space:]]+)*fun[[:space:]]+(<[^>]*>[[:space:]]*)?([A-Za-z0-9_<>?, ]+\.)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*\(.*/\4/p' |
        grep -E '^.{4,}$' | sort -u | head -n 30 || true
}

# Related unit tests. Each changed Kotlin file contributes names: its basename and, while the file
# exists, the names it declares. Test files named <basename>*Test.kt and test files mentioning any
# of the names as a whole word are related, as is a changed test file itself. A changed test
# resource is related to the tests naming its file; one no test names runs the whole suite.
test_files=()
names=()
wear_tests=false
benchmark_tests=false
for path in "${code[@]}"; do
    case "$path" in
        wear/*) wear_tests=true ;;
        src/benchmark/* | src/testBenchmark/*) benchmark_tests=true ;;
    esac
    case "$path" in
        src/test/resources/*)
            name="$(basename "$path")"
            if grep -rqwF --include='*.kt' -- "$name" src/test/java; then
                names+=("$name")
            elif [[ -z "$whole_suite" ]]; then
                whole_suite="$path changed and no test names it"
            fi
            ;;
        *.kt | *.java)
            name="$(basename "${path%.*}")"
            names+=("$name")
            [[ "$path" == src/test/java/* && -f "$path" ]] && test_files+=("$path")
            while IFS= read -r file; do
                test_files+=("$file")
            done < <(find src/test/java -name "${name}*Test.kt")
            if [[ -f "$path" && "$path" == *.kt ]]; then
                while IFS= read -r declared; do
                    names+=("$declared")
                done < <(declared_names "$path")
            fi
            ;;
    esac
done
if [[ ${#names[@]} -gt 0 ]]; then
    pattern_file="$(mktemp)"
    # shellcheck disable=SC2154 # assigned inside the trap
    trap 'quick_status=$?; rm -f "$pattern_file"; finish "$quick_status"' EXIT
    printf '%s\n' "${names[@]}" | sort -u >"$pattern_file"
    while IFS= read -r file; do
        test_files+=("$file")
    done < <(grep -rlwF --include='*.kt' -f "$pattern_file" src/test/java || true)
    # The wear module compiles the app's main sources, so its tests can depend on them too.
    if grep -rqwF --include='*.kt' -f "$pattern_file" wear/src/test; then
        wear_tests=true
    fi
fi

# The --tests filters are the class names declared in related test files that hold tests; a file
# may declare a class not named after it.
tests=()
while IFS= read -r test; do
    [[ -n "$test" ]] && tests+=("$test")
done < <(
    for file in ${test_files[@]+"${test_files[@]}"}; do
        grep -q '@Test' "$file" || continue
        sed -nE 's/^([a-z]+[[:space:]]+)*class[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\2/p' "$file"
    done | sort -u
)
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
    # The benchmark variant's own tests, as CI's benchmark isolation job runs them.
    echo "quick-check: benchmark sources changed, running the benchmark variant's tests"
    tasks+=(:testBenchmarkUnitTest --tests '*FullChatFixtureRepositoryTest' --tests '*FakeRelayTransportTest')
fi

./gradlew ${gradle_args[@]+"${gradle_args[@]}"} "${tasks[@]}"
