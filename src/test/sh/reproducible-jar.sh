#!/usr/bin/env bash
set -eu

# Compare the tested artifact with a later build from a separate source directory.
# The exported REVISION supplies the same metadata to this copy without .git.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src"
cp Makefile "$work/"
cp -R src/main "$work/src/"
sleep 2  # ZIP timestamps have two-second resolution.
"$1" -s -C "$work" build JAVAC="$2" JAR="$3"
cmp "$4" "$work/build/music-recommender.jar"
