#!/usr/bin/env bash
# PreToolUse gate (Bash): before a `git commit`, run ./gradlew check (compile, every test, JaCoCo gate) and
# block the commit if it fails. Blocks with exit 2 and the reason on stderr.
#
# Deadline: a hook that hits its settings.json timeout is a non-blocking error, so the commit would go
# through unchecked. The script therefore stops check itself at CHECK_DEADLINE seconds and blocks. A measured
# `./gradlew check --rerun-tasks` took 67 s (2026-10-06); 300 s leaves room for cold Docker and container
# starts. The settings.json timeout (330 s) must stay above it.
set -uo pipefail

# Fail closed: without jq the hook can't read its input, and an empty input would let everything through.
command -v jq >/dev/null || { echo "Blocked: jq missing. Install it (brew install jq); this hook needs it to read the tool input." >&2; exit 2; }

cmd=$(jq -r '.tool_input.command // empty')
# `git commit` anywhere in the command (after &&, ;, |, a subshell), allowing global options such as
# `git -C dir commit`. Not matched: `git log --grep commit`, `git commit-tree`.
grep -Eq '(^|[;&|(`[:space:]])git([[:space:]]+-[^[:space:]]+([[:space:]]+[^-[:space:]][^[:space:]]*)?)*[[:space:]]+commit([[:space:];&|)`]|$)' <<<"$cmd" \
  || exit 0

CHECK_DEADLINE="${CHECK_DEADLINE:-300}"
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
cd "$root" || exit 1
log="$root/build/hooks/check-before-commit.log"
mkdir -p "$(dirname "$log")"

# Results older than this marker are from an earlier run (a compile failure writes none), so they never name a test.
marker="$root/build/hooks/check-started"
touch "$marker"
./gradlew -q check >"$log" 2>&1 &
pid=$!
start=$SECONDS
while kill -0 "$pid" 2>/dev/null; do
  if (( SECONDS - start >= CHECK_DEADLINE )); then
    pkill -P "$pid" 2>/dev/null; kill "$pid" 2>/dev/null
    echo "Blocked: ./gradlew check did not finish within ${CHECK_DEADLINE}s, so the commit is unchecked. Log: $log" >&2
    exit 2
  fi
  sleep 1
done
wait "$pid"
status=$?
if (( status != 0 )); then
  {
    echo "Blocked: ./gradlew check failed (exit $status), so the commit did not run. Fix it, then commit again."
    grep -E 'FAILED|error:|What went wrong|Rule violated|tests completed|> ' "$log" | head -30
    # -q hides test names; the JUnit XML results have them.
    failed=$(find build/test-results/test -name 'TEST-*.xml' -newer "$marker" -exec grep -l '<failure' {} + 2>/dev/null \
      | sed 's|.*/TEST-||; s|\.xml$||')
    [[ -n "$failed" ]] && echo "Failing test classes:" && echo "$failed"
    echo "Full log: $log"
  } >&2
  exit 2
fi
exit 0
