#!/usr/bin/env bash
set -eu

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src/main/java" "$work/scripts"
cp scripts/source-revision.sh "$work/scripts/"
cd "$work"
revision() { sh scripts/source-revision.sh; }

[[ $(revision) == unknown ]]
git init -q
printf 'class Song {}\n' > src/main/java/Song.java
printf '/build/\n' > .gitignore
git add .
git -c user.name=Test -c user.email=test@example.invalid commit -qm fixture
head_sha=$(git rev-parse HEAD)
mkdir build
touch build/generated.jar
[[ $(revision) == "$head_sha" ]]
echo '// edit' >> src/main/java/Song.java
[[ $(revision) == "$head_sha-dirty" ]]
git add src/main/java/Song.java
[[ $(revision) == "$head_sha-dirty" ]]
git restore --staged --worktree src/main/java/Song.java
touch src/main/java/New.java
[[ $(revision) == "$head_sha-dirty" ]]
