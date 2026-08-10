#!/usr/bin/env bash
#
# FORK-ONLY: Pull upstream Mindful changes into this fork's feature branch.
#
# The layout this assumes:
#   main      - pristine mirror of akaMrNagar/Mindful. Never commit here.
#   cooldown  - the fork's own work, kept rebased on top of main.
#
# Keeping main pristine is what lets GitHub's "Sync fork" button work, and keeps
# every upstream merge a single well-defined step.
#
# Usage: ./scripts/sync-upstream.sh [feature-branch]   (default: cooldown)

set -euo pipefail

FEATURE_BRANCH="${1:-cooldown}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Refuse to run on a dirty tree - a merge on top of uncommitted work is how you
# lose changes you can't recover.
if [[ -n "$(git status --porcelain)" ]]; then
  echo "✗ Working tree has uncommitted changes. Commit or stash first:"
  git status --short
  exit 1
fi

echo "→ Fetching upstream..."
git fetch upstream --quiet

# Fast-forward main to upstream. Guaranteed to be a fast-forward as long as
# nothing was ever committed directly to main.
echo "→ Updating main to match upstream..."
git checkout main --quiet
if ! git merge --ff-only upstream/main --quiet; then
  echo "✗ main has diverged from upstream, so it can't fast-forward."
  echo "  Something was committed to main directly. To discard those commits:"
  echo "      git checkout main && git reset --hard upstream/main"
  exit 1
fi
git push origin main --quiet
echo "  main is now at $(git rev-parse --short main)"

# Merge the refreshed main into the feature branch.
echo "→ Merging main into $FEATURE_BRANCH..."
git checkout "$FEATURE_BRANCH" --quiet
if git merge main --no-edit; then
  echo ""
  echo "✓ $FEATURE_BRANCH is up to date with upstream."
  echo "  Next: ./scripts/build-apk.sh    then push when you're happy."
else
  echo ""
  echo "⚠ Merge conflicts - resolve them, then:"
  echo "      git add <files> && git commit"
  echo "  Or back out entirely with: git merge --abort"
  exit 1
fi
