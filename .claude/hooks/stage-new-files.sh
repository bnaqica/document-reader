#!/usr/bin/env bash
# PostToolUse hook for Write, Edit, NotebookEdit and Bash.
#
# Project rule: new files are added to git as soon as they exist, so they show up in `git status` as staged and
# can't be left out of a commit by accident. This stages every new file that .gitignore doesn't exclude.
# Edits to files git already tracks are not staged; the developer stages those when committing.
#
# It never blocks anything: it always exits 0, and prints nothing on success.

set -uo pipefail

cat >/dev/null   # the tool input isn't needed; read it so the caller's pipe doesn't break

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

if [ -n "$(git ls-files --others --exclude-standard)" ]; then
  git ls-files -z --others --exclude-standard | xargs -0 git add -- >/dev/null 2>&1
fi

exit 0
