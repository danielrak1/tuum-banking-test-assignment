#!/usr/bin/env bash
# PostToolUse gate (Edit|Write|MultiEdit): after a .java edit, compile main and test sources.
# A compile error exits 2 with the compiler output on stderr, which Claude sees at once. The edit itself is
# already on disk; the gate makes the breakage loud instead of waiting for the next test run.
# Known gap: only Claude's file tools pass through here; a Bash edit (sed -i, heredoc) does not.
set -uo pipefail

# Fail closed: without jq the hook can't read its input, and an empty input would let everything through.
command -v jq >/dev/null || { echo "Blocked: jq missing. Install it (brew install jq); this hook needs it to read the tool input." >&2; exit 2; }

file=$(jq -r '.tool_input.file_path // empty')
[[ "$file" == *.java ]] || exit 0

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}" || exit 1
# compileTestJava depends on compileJava, so one task covers both source sets.
if ! out=$(./gradlew -q compileTestJava 2>&1); then
  {
    echo "Compile failed after editing $file:"
    # javac's errors, then Gradle's summary up to its generic "* Try:" advice.
    echo "$out" | sed '/^\* Try:/,$d' | grep -v '^[[:space:]]*$' | head -40
  } >&2
  exit 2
fi
exit 0
