#!/usr/bin/env bash
#
# seam-tag — tag containers from a terminal, so the reporter has something to sweep.
#
# In-game tagging (sneak + right-click a chest, pick a project) is MCO-261, and it is Phase C.
# Until it exists there is no way to create a container tag from inside Minecraft, which would
# leave the Phase B reporter untestable: it would connect, find nothing tagged, and correctly do
# nothing. This is the stopgap — the same `/api/v1` endpoints the mod will call, driven by hand.
#
# It signs in the same way the mod does, with the device-code flow, so the token it gets is an
# ordinary player token belonging to you. Nothing here is reporter-specific: a reporter token
# deliberately CANNOT tag, because tagging records who did it.
#
#   scripts/seam-tag.sh login                          # once; prints a URL to approve in a browser
#   scripts/seam-tag.sh worlds
#   scripts/seam-tag.sh projects <world_id>
#   scripts/seam-tag.sh tag   <world_id> <project_id> <x> <y> <z> [kind] [dimension] [group_key]
#   scripts/seam-tag.sh tags  <world_id>
#   scripts/seam-tag.sh untag <world_id> <container_id>
#   scripts/seam-tag.sh storage <world_id>             # what the sweep has measured so far
#
# Point it somewhere other than a local webapp with SEAM_API_BASE_URL.
#
# Both halves of a joined double chest are tagged separately, with the SAME group_key — the
# position of one of them — or the sweep will count the pair twice. The webapp only accepts a
# group_key that is the container's own position or an adjacent one.
#
#   scripts/seam-tag.sh tag 1 7 10 64 20 chest minecraft:overworld 10,64,20
#   scripts/seam-tag.sh tag 1 7 11 64 20 chest minecraft:overworld 10,64,20

set -euo pipefail

API="${SEAM_API_BASE_URL:-http://localhost:8080}/api/v1"
TOKEN_FILE="${SEAM_TAG_TOKEN_FILE:-$(dirname "$0")/../.seam-tag-token}"

die() { echo "seam-tag: $*" >&2; exit 1; }

for tool in curl jq; do
    command -v "$tool" >/dev/null || die "$tool is required"
done

token() {
    [[ -f "$TOKEN_FILE" ]] || die "not signed in — run: $0 login"
    cat "$TOKEN_FILE"
}

# curl that fails loudly. The API answers every error as {"error":..,"message":..}, so show that
# rather than an empty body and a bare exit code.
call() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-sS -X "$method" -H "Authorization: Bearer $(token)" -w '\n%{http_code}')
    [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")

    local out status
    out="$(curl "${args[@]}" "$API$path")"
    status="${out##*$'\n'}"
    out="${out%$'\n'*}"
    if [[ "$status" -ge 400 ]]; then
        echo "$out" | jq . 2>/dev/null || echo "$out"
        die "HTTP $status from $method $path"
    fi
    echo "$out"
}

cmd_login() {
    local start code device_code interval verification_uri
    start="$(curl -sS -X POST "$API/auth/device-code")" ||
        die "could not reach $API — is the webapp running?"

    device_code="$(jq -r .device_code <<<"$start")"
    code="$(jq -r .user_code <<<"$start")"
    verification_uri="$(jq -r .verification_uri <<<"$start")"
    interval="$(jq -r .interval <<<"$start")"
    [[ "$device_code" != "null" ]] || { jq . <<<"$start"; die "unexpected device-code response"; }

    echo "Open:  $verification_uri"
    echo "Code:  $code"
    echo
    echo "Waiting for you to approve it..."

    while true; do
        sleep "$interval"
        local poll access
        poll="$(curl -sS -X POST -H 'Content-Type: application/json' \
            -d "{\"device_code\":\"$device_code\"}" "$API/auth/device-code/poll")"
        access="$(jq -r '.access_token // empty' <<<"$poll")"
        if [[ -n "$access" ]]; then
            printf '%s' "$access" > "$TOKEN_FILE"
            chmod 600 "$TOKEN_FILE"
            echo "Signed in as $(jq -r .username <<<"$poll"). Token saved to $TOKEN_FILE."
            return
        fi
        case "$(jq -r '.error // "?"' <<<"$poll")" in
            authorization_pending) ;;
            slow_down) interval=$((interval + 2)) ;;
            *) jq . <<<"$poll"; die "sign-in failed" ;;
        esac
    done
}

cmd_worlds()   { call GET "/worlds" | jq -r '.[] | "\(.id)\t\(.name)"'; }
cmd_projects() { call GET "/worlds/$1/projects" | jq -r '.[] | "\(.id)\t\(.stage)\t\(.name)"'; }
cmd_tags()     { call GET "/worlds/$1/containers" | jq -r '.containers[] | "\(.id)\tproject \(.project_id)\t\(.x),\(.y),\(.z)\t\(.kind)\t\(.state)\tseen \(.last_seen_at // "never")"'; }
cmd_untag()    { call DELETE "/worlds/$1/containers/$2" >/dev/null && echo "untagged $2"; }
cmd_storage()  { call GET "/worlds/$1/storage" | jq -r '.[] | "project \(.project_id)\t\(.item_id)\t\(.measured)\tin \(.container_count) container(s)\tsince \(.oldest_seen_at // "-")"'; }

cmd_tag() {
    [[ $# -ge 5 ]] || die "usage: $0 tag <world_id> <project_id> <x> <y> <z> [kind] [dimension] [group_key]"
    local world="$1" project="$2" x="$3" y="$4" z="$5"
    local kind="${6:-chest}" dimension="${7:-minecraft:overworld}" group="${8:-}"

    local body
    body="$(jq -nc --arg d "$dimension" --argjson x "$x" --argjson y "$y" --argjson z "$z" \
        --argjson p "$project" --arg k "$kind" --arg g "$group" \
        '{dimension:$d, x:$x, y:$y, z:$z, project_id:$p, kind:$k}
         + (if $g == "" then {} else {group_key:$g} end)')"

    call POST "/worlds/$world/containers" "$body" |
        jq -r '"tagged \(.id)\tproject \(.project_id)\t\(.x),\(.y),\(.z)\tgroup \(.group_key)"'
}

case "${1:-}" in
    login)    shift; cmd_login "$@" ;;
    worlds)   shift; cmd_worlds "$@" ;;
    projects) shift; cmd_projects "$@" ;;
    tag)      shift; cmd_tag "$@" ;;
    tags)     shift; cmd_tags "$@" ;;
    untag)    shift; cmd_untag "$@" ;;
    storage)  shift; cmd_storage "$@" ;;
    *) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
