#!/usr/bin/env bash
# Create-transaction load test (docs/performance.md). Per run, on an isolated compose stack (project banking-perf):
#   1. docker compose down -v, then up --wait   a fresh stack, so no run sees an earlier run's rows
#   2. k6 MODE=seed                             accounts (1,000 for spread, 1 for hot), each funded with one IN
#   3. wait until outbox_event is empty         the measured lag is the load's alone, not the seed's
#   4. k6 MODE=load                             warm-up, then the measured window; outbox sampled once a second
#   5. sample the drain for up to 60 s          the publisher's own rate, with no HTTP load competing; the full
#                                               drain time is extrapolated from it (the next run starts clean anyway)
# k6 runs from its Docker image inside the compose network (http://app:8080), so Docker Desktop's port
# forwarding isn't measured. The stack is always torn down, even after a failure or Ctrl-C.
#
# Usage: scripts/load-test.sh [--label NAME] [--runs N] [--scenario spread|hot|all]
#   --label     names the result files in build/perf/ (default: run), e.g. before / after
#   --runs      runs per scenario (default 3); the summary reports the median
#   --scenario  default all (spread, then hot)
# Env: VUS (50), WARMUP (20s), DURATION (60s), K6_IMAGE (grafana/k6:2.3.0),
#      PERF_APP_PORT (28080), PERF_POSTGRES_PORT (25432), PERF_AMQP_PORT (35673), PERF_RABBITMQ_UI_PORT (35672).
# Exit: 0 if every run passed its k6 thresholds (> 99.9% 201s), 1 otherwise, 2 on a usage or setup error.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

LABEL="run" RUNS=3 SCENARIOS=(spread hot)
while [[ $# -gt 0 ]]; do
    case "$1" in
        --label|--runs)
            [[ $# -ge 2 ]] || { echo "load-test: $1 needs a value" >&2; exit 2; }
            if [[ "$1" == --label ]]; then LABEL="$2"; else RUNS="$2"; fi
            shift 2 ;;
        --scenario)
            case "${2:-}" in
                spread|hot) SCENARIOS=("$2") ;;
                all) SCENARIOS=(spread hot) ;;
                *) echo "load-test: --scenario must be spread, hot or all" >&2; exit 2 ;;
            esac
            shift 2 ;;
        *) echo "usage: $0 [--label NAME] [--runs N] [--scenario spread|hot|all]" >&2; exit 2 ;;
    esac
done
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || { echo "load-test: --runs must be a positive integer" >&2; exit 2; }
command -v jq >/dev/null || { echo "load-test: jq is required" >&2; exit 2; }

PROJECT="banking-perf"
NETWORK="${PROJECT}_default"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.3.0}"
export VUS="${VUS:-50}" WARMUP="${WARMUP:-20s}" DURATION="${DURATION:-60s}"
export APP_PORT="${PERF_APP_PORT:-28080}"
export POSTGRES_PORT="${PERF_POSTGRES_PORT:-25432}"
export AMQP_PORT="${PERF_AMQP_PORT:-35673}"
export RABBITMQ_UI_PORT="${PERF_RABBITMQ_UI_PORT:-35672}"
OUT="$ROOT/build/perf"
DRAIN_TIMEOUT=300   # seconds to wait for the seed's events to be published
DRAIN_WINDOW=60     # seconds to sample the drain after the load
mkdir -p "$OUT"
SUMMARY="$OUT/$LABEL-summary.tsv"
failed=0 sampler=""

compose() { docker compose -p "$PROJECT" "$@"; }
teardown() { compose down -v --remove-orphans >"$OUT/down.log" 2>&1; }
trap '[[ -n "$sampler" ]] && kill "$sampler" 2>/dev/null; teardown' EXIT
trap 'echo "interrupted" >&2; exit 130' INT TERM

# One outbox sample: epoch seconds, backlog, age in seconds of the oldest pending event (the publish lag).
outbox_sample() {
    compose exec -T postgres psql -U banking -d banking -tAF, -c \
        "SELECT round(extract(epoch FROM clock_timestamp())::numeric, 3), count(*),
                coalesce(round(extract(epoch FROM clock_timestamp() - min(created_at))::numeric, 3), 0)
         FROM outbox_event"
}

# sample_until_empty CSV SECONDS: samples into CSV once a second until the backlog is 0 or SECONDS pass.
# Returns 0 if it reached 0, 1 if time ran out or sampling failed.
sample_until_empty() {
    local csv="$1" deadline=$((SECONDS + $2)) sample
    while (( SECONDS < deadline )); do
        sample="$(outbox_sample)" || return 1
        echo "$sample" >>"$csv"
        [[ "$(cut -d, -f2 <<<"$sample")" == "0" ]] && return 0
        sleep 1
    done
    return 1
}

k6() {
    docker run --rm --network "$NETWORK" -v "$OUT:/out" -v "$ROOT/scripts/k6:/scripts:ro" \
        -e BASE_URL=http://app:8080 -e OUT_DIR=/out -e VUS -e WARMUP -e DURATION "$@"
}

machine() {
    echo "date:        $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit:      $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- src || echo ' (+ uncommitted src changes)')"
    echo "host:        $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(sysctl -n hw.ncpu 2>/dev/null) cores," \
        "$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GB, $(uname -sr)"
    echo "docker:      $(docker info --format '{{.OperatingSystem}} {{.ServerVersion}}, VM {{.NCPU}} CPUs, {{.MemTotal}} bytes')"
    echo "k6:          $K6_IMAGE"
    echo "load:        VUS=$VUS WARMUP=$WARMUP DURATION=$DURATION"
}

# run SCENARIO N: one fresh stack, seed, drain, load, drain. Appends a line to SUMMARY.
run() {
    local scenario="$1" n="$2" name="$LABEL-$1-$2" csv k6_rc start_line
    csv="$OUT/$name-outbox.csv"
    echo "==> $name"
    teardown
    compose up --wait --quiet-pull >"$OUT/$name-up.log" 2>&1 \
        || { echo "load-test: compose up failed, see build/perf/$name-up.log" >&2; return 2; }

    k6 -e MODE=seed -e SCENARIO="$scenario" "$K6_IMAGE" run --quiet /scripts/create-transaction.js \
        >"$OUT/$name-seed.log" 2>&1 \
        || { echo "load-test: seeding failed, see build/perf/$name-seed.log" >&2; return 2; }
    sample_until_empty "$OUT/$name-seed-outbox.csv" "$DRAIN_TIMEOUT" \
        || { echo "load-test: the seed's events were not published within ${DRAIN_TIMEOUT}s" >&2; return 2; }

    echo "epoch,backlog,lag_seconds" >"$csv"
    rm -f "$OUT/$name.json" "$OUT/sampler.stop"   # a failed k6 must not leave an earlier run's numbers behind
    # Stopped through a file, not kill: kill would leave an in-flight psql to append a sample after start_line.
    ( while [[ ! -f "$OUT/sampler.stop" ]]; do outbox_sample >>"$csv" 2>/dev/null; sleep 1; done ) &
    sampler=$!
    k6 -e MODE=load -e SCENARIO="$scenario" -e RESULT="$name.json" "$K6_IMAGE" run --quiet \
        /scripts/create-transaction.js >"$OUT/$name-k6.log" 2>&1
    k6_rc=$?
    touch "$OUT/sampler.stop"; wait "$sampler" 2>/dev/null; sampler=""; rm -f "$OUT/sampler.stop"
    start_line=$(( $(wc -l <"$csv") + 1 ))   # the first sample after k6 ended
    sample_until_empty "$csv" "$DRAIN_WINDOW"
    tail -1 "$OUT/$name-k6.log" | sed 's/^/    /'
    if [[ "$k6_rc" -ne 0 || ! -f "$OUT/$name.json" ]]; then
        echo "    FAIL: k6 exited $k6_rc, see build/perf/$name-k6.log" >&2
        failed=1
    fi
    [[ -f "$OUT/$name.json" ]] || return 0

    # When k6 ended (the first sample after it): the backlog and the age of the oldest pending event. The drain
    # rate comes from the samples after that, and the full drain time is extrapolated from it.
    local outbox
    outbox="$(awk -F, -v start="$start_line" '
        NR == start { t0 = $1; left = $2; lag = $3; seen = 1 }
        NR > start { t1 = $1; now = $2 }
        END {
            if (!seen) { printf "-\t-\t-\t-"; exit }   # no sample after the load: nothing to report
            rate = "-"; est = (left == 0) ? "0" : "-"
            if (t1 > t0 && left > now) { r = (left - now) / (t1 - t0); rate = sprintf("%.0f", r); est = sprintf("%.0f", left / r) }
            printf "%d\t%.1f\t%s\t%s", left, lag, rate, est
        }' "$csv")"
    jq -r --arg name "$name" --arg outbox "$outbox" \
        '[$name, .scenario, (.tps * 10 | round / 10), (.latencyMs.p50 * 10 | round / 10),
          (.latencyMs.p95 * 10 | round / 10), (.latencyMs.p99 * 10 | round / 10), (.okRate * 10000 | round / 100)]
         | join("\t") + "\t" + $outbox' "$OUT/$name.json" >>"$SUMMARY"
    echo "    outbox at load end: $(tr '\t' ' ' <<<"$outbox") (backlog, lag s, drain events/s, est. drain s)"
}

machine | tee "$OUT/$LABEL-machine.txt"
compose build --quiet >"$OUT/build.log" 2>&1 || { echo "load-test: image build failed, see build/perf/build.log" >&2; exit 2; }
printf 'run\tscenario\ttps\tp50_ms\tp95_ms\tp99_ms\tok_pct\tbacklog_at_end\tlag_at_end_s\tdrain_eps\test_drain_s\n' >"$SUMMARY"

for scenario in "${SCENARIOS[@]}"; do
    for n in $(seq 1 "$RUNS"); do
        run "$scenario" "$n"
        rc=$?
        [[ "$rc" -eq 0 ]] || exit "$rc"
    done
done

# The median of each column per scenario (the middle run when sorted by that column).
echo
echo "Median of $RUNS run(s), label $LABEL (all runs: build/perf/$LABEL-summary.tsv)"
{
    printf 'scenario\ttps\tp50_ms\tp95_ms\tp99_ms\tok_pct\tbacklog_at_end\tlag_at_end_s\tdrain_eps\test_drain_s\n'
    for scenario in "${SCENARIOS[@]}"; do
        line="$scenario"
        for col in $(seq 3 11); do
            # Numeric cells only: a "-" (nothing measured) would sort as a value and shift the median.
            line+="\t$(awk -F'\t' -v s="$scenario" -v c="$col" 'NR > 1 && $2 == s && $c ~ /^[0-9.]+$/ { print $c }' "$SUMMARY" |
                sort -g | awk '{ v[NR] = $0 } END { print (NR ? v[int((NR + 1) / 2)] : "-") }')"
        done
        printf '%b\n' "$line"
    done
} | tee "$OUT/$LABEL-median.tsv" | column -t -s $'\t'
exit "$failed"
