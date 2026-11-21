# Hooks

Hook scripts referenced from `.claude/settings.json`.

| Script | Event | What it does |
|---|---|---|
| `block-git-commit.sh` | `PreToolUse` (Bash) | Blocks `git commit/push/merge/rebase/cherry-pick/revert/am` and `gh pr create/merge`, so Claude never commits. The developer reviews and commits every change. |
| `stage-new-files.sh` | `PostToolUse` (Write, Edit, NotebookEdit, Bash) | Runs `git add` on every new file that `.gitignore` doesn't exclude, so new files are in git straight away. Edits to tracked files aren't staged. It never blocks. |

Test it:

```bash
echo '{"tool_input":{"command":"git commit -m x"}}' | .claude/hooks/block-git-commit.sh; echo "exit $?"   # exit 2 = blocked
echo '{"tool_input":{"command":"git status"}}'      | .claude/hooks/block-git-commit.sh; echo "exit $?"   # exit 0 = allowed
```

Test the staging hook. It should show `A  scratch.txt` (staged), then clean up:

```bash
touch scratch.txt && echo '{}' | .claude/hooks/stage-new-files.sh && git status --short scratch.txt && git rm --cached -q scratch.txt && rm scratch.txt
```
