#!/usr/bin/env bash
set -eu

log=$(mktemp)
"$1" -jar "$2" serve --port 0 >"$log" 2>&1 &
server_pid=$!
cleanup() {
    kill -KILL "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    rm -f "$log"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

deadline=$((SECONDS + 15))
while (( SECONDS < deadline )); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
        cat "$log" >&2
        echo "Packaged server exited before readiness" >&2
        exit 1
    fi
    port=$(sed -n 's|^Listening on http://127\.0\.0\.1:\([0-9][0-9]*\)$|\1|p' "$log")
    if [[ -n "$port" ]]; then
        if response=$(curl --noproxy '*' -fs --max-time 1 "http://127.0.0.1:$port/ready"); then
            [[ $response == '{"status":"ready"}' ]] && exit 0
            echo "Unexpected readiness response: $response" >&2
            exit 1
        fi
    fi
    sleep 0.1
done
cat "$log" >&2
echo "Timed out waiting for packaged server readiness" >&2
exit 1
