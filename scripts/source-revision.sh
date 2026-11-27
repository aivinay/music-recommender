#!/bin/sh
# Scope Git commands to this checkout, even when building an archive inside another repository.
revision=$(git --git-dir=.git rev-parse --verify HEAD 2>/dev/null) || { echo unknown; exit 0; }
changes=$(git --git-dir=.git status --porcelain --untracked-files=all -- Makefile scripts/source-revision.sh src/main/java) || { echo unknown; exit 0; }
if [ -n "$changes" ]; then
    revision="$revision-dirty"
fi
echo "$revision"
