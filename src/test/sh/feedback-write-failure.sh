#!/usr/bin/env bash
set -eu

java_bin=$1
jar=$2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
feedback="$work/feedback.tsv"
# Start with a valid file larger than the child process may write.
awk 'BEGIN { print "user_id\ttrack_id"; for (i = 0; i < 500; i++) print "listener-" i "\tt01" }' > "$feedback"
chmod 640 "$feedback"
check_mode() {
    [[ $(LC_ALL=C ls -ld "$feedback" | cut -c 2-10) == rw-r----- ]]
}
cp "$feedback" "$work/original.tsv"
status=0
(
    # Request an I/O error when the replacement exceeds the limit.
    trap '' XFSZ
    ulimit -f 1
    LC_ALL=C exec "$java_bin" -XX:-UsePerfData -jar "$jar" hide maya t04 --feedback "$feedback"
) 2>"$work/error" || status=$?
if [[ $status != 1 ]] || ! grep -qi 'file too large' "$work/error"; then
    cat "$work/error" >&2
    echo "Expected a feedback write failure, got exit status $status" >&2
    exit 1
fi
cmp "$work/original.tsv" "$feedback"
check_mode
# A fresh invocation can retry using the original data.
"$java_bin" -jar "$jar" hide maya t04 --feedback "$feedback"
printf 'maya\tt04\n' >> "$work/original.tsv"
cmp "$work/original.tsv" "$feedback"
check_mode
"$java_bin" -jar "$jar" unhide maya t04 --feedback "$feedback"
check_mode
