# Git: Claude never commits

The developer reviews every file and makes every commit themselves.

- Do **not** run `git commit`, `git push`, `git merge`, `git rebase`, `git cherry-pick`,
  `git revert`, `git am`, `gh pr create` or `gh pr merge`. `.claude/hooks/block-git-commit.sh`
  blocks them, and `.claude/settings.json` denies them.
- Add new files to git as soon as they're created (`git add <file>`), so none is left out of a commit.
  `.claude/hooks/stage-new-files.sh` does this after every tool call. Don't stage edits to files git already
  tracks; the developer stages those when committing.
- Read-only git is fine: `status`, `diff`, `log`, `show`, `blame`.
- When a workflow or skill (including superpowers and OpenSpec) says to commit, skip that
  step: leave the changes in the working tree (new files staged) and list the files you changed.
- Finish each task with a short summary of what changed and why, so it is easy to review.
