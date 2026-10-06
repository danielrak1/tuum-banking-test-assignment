#!/usr/bin/env bash
# The full local feedback loop in one command (docs/test-plan.md, "How to run"):
#   1. ./gradlew test --rerun check  compile, every test (always re-run, never UP-TO-DATE), JaCoCo gate
#   2. docker compose up --build     an isolated stack (project banking-verify, its own ports and volumes)
#   3. scripts/contract-check.sh     the PDF's requests and errors, plus the events, against that stack
#   4. docker compose down -v        always, even after a failure or Ctrl-C
# A failed step skips the steps after it. Ends with one summary table; exits non-zero if anything failed.
#
# Usage: .claude/skills/verify/verify.sh [--skip-check]
#   --skip-check  start at step 2 (the tests already passed and only the stack needs checking)
# Ports: VERIFY_APP_PORT (18080), VERIFY_POSTGRES_PORT (15432), VERIFY_AMQP_PORT (25673),
#        VERIFY_RABBITMQ_UI_PORT (25672). They differ from the dev stack's, so both can run at once.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT" || exit 2

PROJECT="banking-verify"
export APP_PORT="${VERIFY_APP_PORT:-18080}"
export POSTGRES_PORT="${VERIFY_POSTGRES_PORT:-15432}"
export AMQP_PORT="${VERIFY_AMQP_PORT:-25673}"
export RABBITMQ_UI_PORT="${VERIFY_RABBITMQ_UI_PORT:-25672}"
LOG_DIR="$ROOT/build/verify"
mkdir -p "$LOG_DIR"
rm -f "$LOG_DIR"/*.log   # a skipped step must not show the last run's output

STEPS=() RESULTS=() TIMES=() NOTES=()
failed=0 stack_started=0 check_ran=0 current=""
# The terminal, kept on fds 3 and 4: a trap that fires during a step inherits that step's redirection to
# its log, so the traps write here explicitly.
exec 3>&1 4>&2

record() { STEPS+=("$1"); RESULTS+=("$2"); TIMES+=("$3"); NOTES+=("$4"); [[ "$2" == FAIL ]] && failed=1; }

# run NAME LOG COMMAND...: runs a step unless an earlier one failed, logging its output to LOG.
run() {
    local name="$1" log="$2" start rc
    shift 2
    if [[ "$failed" -ne 0 ]]; then
        record "$name" SKIP "-" "an earlier step failed"
        return
    fi
    echo "==> $name (log: ${log#"$ROOT"/})"
    start=$SECONDS
    current="$name"
    "$@" >"$log" 2>&1
    rc=$?
    current=""
    if [[ "$rc" -eq 0 ]]; then
        record "$name" PASS "$((SECONDS - start))s" ""
    else
        record "$name" FAIL "$((SECONDS - start))s" "exit $rc, see ${log#"$ROOT"/}"
    fi
}

compose() { docker compose -p "$PROJECT" "$@"; }

teardown() {
    if [[ "$stack_started" -eq 1 ]]; then
        compose logs --no-color >"$LOG_DIR/compose.log" 2>&1
        local start=$SECONDS
        if compose down -v --remove-orphans >"$LOG_DIR/down.log" 2>&1; then
            record "compose down -v" PASS "$((SECONDS - start))s" "stack logs: build/verify/compose.log"
        else
            record "compose down -v" FAIL "$((SECONDS - start))s" "see build/verify/down.log"
        fi
        stack_started=0
    fi
}

summary() {
    teardown
    echo
    printf '%-30s %-5s %-6s %s\n' "STEP" "RESULT" "TIME" "NOTE"
    if (( ${#STEPS[@]} )); then   # empty after an interrupt before the first step ends; set -u on old bash
        for i in "${!STEPS[@]}"; do
            printf '%-30s %-5s  %-6s %s\n' "${STEPS[$i]}" "${RESULTS[$i]}" "${TIMES[$i]}" "${NOTES[$i]}"
        done
    fi
    # Only after check ran in this invocation: an older report would show stale numbers.
    if [[ "$check_ran" -eq 1 && -f build/reports/jacoco/test/jacocoTestReport.xml ]]; then
        echo
        echo "Coverage: $(coverage)   report: build/reports/jacoco/test/html/index.html"
    fi
    echo
    if [[ "$failed" -eq 0 ]]; then echo "VERIFY PASSED"; else echo "VERIFY FAILED"; fi
}

# Line and branch coverage from the JaCoCo XML report (its last, report-level counters).
coverage() {
    local xml=build/reports/jacoco/test/jacocoTestReport.xml type missed covered out=""
    for type in LINE BRANCH; do
        read -r missed covered < <(grep -o "<counter type=\"$type\" missed=\"[0-9]*\" covered=\"[0-9]*\"/>" "$xml" |
            tail -1 | sed -E 's/.*missed="([0-9]+)" covered="([0-9]+)".*/\1 \2/')
        out+="$(echo "$type" | tr '[:upper:]' '[:lower:]') $(awk -v m="$missed" -v c="$covered" \
            'BEGIN { printf "%.1f%%", (m + c) ? 100 * c / (m + c) : 0 }')  "
    done
    echo "$out"
}

# Keep a non-zero status (130 after an interrupt); otherwise exit 1 if any step failed.
trap 'rc=$?; summary >&3 2>&4; (( rc == 0 && failed )) && rc=1; exit $rc' EXIT
trap 'echo "interrupted" >&4; [[ -n "$current" ]] && record "$current" FAIL "-" "interrupted"; failed=1; exit 130' INT TERM

if [[ "${1:-}" == "--skip-check" ]]; then
    record "./gradlew test --rerun check" SKIP "-" "--skip-check"
else
    rm -f build/reports/jacoco/test/jacocoTestReport.xml
    check_ran=1
    # --rerun: Gradle would otherwise skip :test as UP-TO-DATE when nothing changed, and "PASS" would mean
    # nothing ran. verify must exercise the tests every time.
    run "./gradlew test --rerun check" "$LOG_DIR/check.log" ./gradlew test --rerun check
fi

if [[ "$failed" -eq 0 ]]; then stack_started=1; fi
run "compose up --build --wait" "$LOG_DIR/up.log" compose up --build --wait --quiet-pull

run "contract check" "$LOG_DIR/contract-check.log" \
    scripts/contract-check.sh "http://localhost:$APP_PORT" "http://localhost:$RABBITMQ_UI_PORT"
if [[ -f "$LOG_DIR/contract-check.log" ]]; then
    tail -1 "$LOG_DIR/contract-check.log" | sed 's/^/    /'
    grep -A1 '^FAIL' "$LOG_DIR/contract-check.log" | sed 's/^/    /'
fi
exit 0   # the EXIT trap turns this into 1 if a step failed
