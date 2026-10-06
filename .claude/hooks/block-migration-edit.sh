#!/usr/bin/env bash
# PreToolUse gate (Edit|Write|MultiEdit): never edit an applied Flyway migration (CLAUDE.md, "Migrations").
# "Applied" means committed: a V*.sql that exists in HEAD is blocked; a new, uncommitted one stays editable
# until it is committed. Blocks with exit 2 and the reason on stderr.
# Known gap: only Claude's file tools pass through here; a Bash edit (sed -i, mv, rm) does not.
set -uo pipefail

# Fail closed: without jq the hook can't read its input, and an empty input would let everything through.
command -v jq >/dev/null || { echo "Blocked: jq missing. Install it (brew install jq); this hook needs it to read the tool input." >&2; exit 2; }

file=$(jq -r '.tool_input.file_path // empty')
case "$file" in
  */db/migration/V*.sql) ;;
  *) exit 0 ;;
esac

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
rel="${file#"$root"/}"
if git -C "$root" cat-file -e "HEAD:$rel" 2>/dev/null; then
  cat >&2 <<EOF
Blocked: $rel is a committed Flyway migration. Flyway checksums applied migrations, so editing it breaks
every database that already ran it. Add a new migration instead: V<next>__<description>.sql.
EOF
  exit 2
fi
exit 0
