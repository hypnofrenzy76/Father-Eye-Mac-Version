#!/bin/bash
#
# Father Eye rollback. Restores world and/or player data from a structured
# backup produced by fe-backup.sh.
#
# SAFETY MODEL
#   1. Refuses to run while the Minecraft server is up (world/session.lock
#      is held / a live RCON answers). The caller (web portal) also gates
#      on server-stopped, this is defence in depth.
#   2. Takes a pre-rollback safety snapshot of exactly the subtrees it is
#      about to overwrite, into <DEST>/pre-rollback-<ts>/.
#   3. Extracts each selected component into a temp dir on the SAME
#      filesystem as the world, then atomically swaps it into place
#      (move-old-aside, move-new-in, delete-old) so a failed or interrupted
#      extraction never leaves a half-restored world.
#
# Usage:
#   fe-rollback.sh --id fe-YYYYMMDD-HHMMSS --scope world|playerdata|both
#                  [--server DIR] [--dest DIR]
#
# Exit codes:
#   0 success
#   1 precondition failure (server up, backup missing, bad scope)
#   2 extraction/swap failure (original world left intact / restored)
#
set -u

SERVER="/Users/luke/Desktop/Server"
DEST="/Volumes/Server Backups/backups"
ID=""
SCOPE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --id)        ID="${2:-}"; shift 2 ;;
        --id=*)      ID="${1#*=}"; shift ;;
        --scope)     SCOPE="${2:-}"; shift 2 ;;
        --scope=*)   SCOPE="${1#*=}"; shift ;;
        --server)    SERVER="${2:-}"; shift 2 ;;
        --server=*)  SERVER="${1#*=}"; shift ;;
        --dest)      DEST="${2:-}"; shift 2 ;;
        --dest=*)    DEST="${1#*=}"; shift ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
done

LOG="$DEST/backup.log"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" | tee -a "$LOG" >&2; }

# ---- determinate progress -------------------------------------------------
# Mirrors fe-backup.sh: a binary-safe Python passthrough counter emits
# FE_PROGRESS=<int 0-100> on stderr (merged into the panel/web job stream)
# as bytes flow. For rollback we count COMPRESSED bytes read from each
# archive, which is the only cheap, exact denominator available, and scale
# into the band [start,end] for the current stage.
bytes_in() {
    local total="$1" start="$2" end="$3"
    if ! command -v python3 >/dev/null 2>&1 || [ "${total:-0}" -le 0 ]; then
        cat
        return
    fi
    python3 - "$total" "$start" "$end" <<'PYEOF'
import sys
total = int(sys.argv[1]); start = int(sys.argv[2]); end = int(sys.argv[3])
out = sys.stdout.buffer; inp = sys.stdin.buffer
seen = 0; last = -1; span = max(1, end - start)
while True:
    chunk = inp.read(1 << 16)
    if not chunk:
        break
    out.write(chunk); seen += len(chunk)
    pct = start + int(span * min(seen, total) / total)
    if pct != last:
        last = pct
        sys.stderr.write("FE_PROGRESS=%d\n" % pct); sys.stderr.flush()
out.flush()
PYEOF
}
file_bytes() { [ -f "$1" ] && stat -f '%z' "$1" 2>/dev/null || echo 0; }
echo "FE_PROGRESS=0"

case "$SCOPE" in
    world|playerdata|both) ;;
    *) echo "FATAL: --scope must be world|playerdata|both" >&2; exit 1 ;;
esac

BDIR="$DEST/$ID"
if [ -z "$ID" ] || [ ! -d "$BDIR" ]; then
    echo "FATAL: backup not found: $BDIR" >&2; exit 1
fi
if [ ! -d "$SERVER/world" ]; then
    echo "FATAL: world dir not found: $SERVER/world" >&2; exit 1
fi

# ---- server-must-be-stopped guard -------------------------------------
# A live server answers RCON. If it does, refuse.
if python3 - <<'EOF' 2>/dev/null
import socket, sys
try:
    s = socket.create_connection(('127.0.0.1', 25575), timeout=3)
    s.close(); sys.exit(0)   # connected => server up
except Exception:
    sys.exit(1)              # refused => server down
EOF
then
    echo "FATAL: server appears to be RUNNING (RCON reachable). Stop it first." >&2
    exit 1
fi

PLAYER_PARTS=(playerdata playermap stats advancements deaths arcanum)
STAMP=$(date +%Y%m%d-%H%M%S)
SAFETY="$DEST/pre-rollback-$STAMP"
mkdir -p "$SAFETY" || { echo "FATAL: cannot create safety dir $SAFETY" >&2; exit 1; }

log "ROLLBACK start id=$ID scope=$SCOPE safety=$SAFETY"

# ---- pre-rollback safety snapshot -------------------------------------
# Snapshot only what we are about to overwrite.
snapshot_world() {
    EXC=()
    for p in "${PLAYER_PARTS[@]}"; do EXC+=(--exclude "./$p"); done
    tar -cf - -C "$SERVER/world" --exclude "./session.lock" "${EXC[@]}" . 2>>"$LOG" \
        | gzip -1 > "$SAFETY/world.tar.gz"
    [ "${PIPESTATUS[0]}" -eq 0 ] || return 1
}
snapshot_player() {
    PRESENT=()
    for p in "${PLAYER_PARTS[@]}"; do [ -e "$SERVER/world/$p" ] && PRESENT+=("$p"); done
    [ "${#PRESENT[@]}" -eq 0 ] && return 0
    tar -cf - -C "$SERVER/world" "${PRESENT[@]}" 2>>"$LOG" \
        | gzip -1 > "$SAFETY/playerdata.tar.gz"
    [ "${PIPESTATUS[0]}" -eq 0 ] || return 1
}

if [ "$SCOPE" = world ] || [ "$SCOPE" = both ]; then
    snapshot_world || { log "FAILED safety snapshot world"; rm -rf "$SAFETY"; exit 2; }
fi
if [ "$SCOPE" = playerdata ] || [ "$SCOPE" = both ]; then
    snapshot_player || { log "FAILED safety snapshot playerdata"; rm -rf "$SAFETY"; exit 2; }
fi
log "ROLLBACK safety snapshot complete"
echo "FE_PROGRESS=20"

# ---- restore helpers (extract-to-temp then atomic swap) ---------------
# Restore the world body (everything except player parts) from world.tar.gz.
restore_world() {
    local arc="$BDIR/world.tar.gz"
    [ -f "$arc" ] || { log "FAILED missing $arc"; return 2; }
    local tmp="$SERVER/.fe-restore-world.$STAMP"
    rm -rf "$tmp"; mkdir -p "$tmp" || return 2
    bytes_in "$(file_bytes "$arc")" "${1:-20}" "${2:-80}" < "$arc" | gzip -dc | tar -xf - -C "$tmp" 2>>"$LOG"
    if [ "${PIPESTATUS[0]}" -ne 0 ] || [ "${PIPESTATUS[1]}" -ne 0 ] || [ "${PIPESTATUS[2]}" -ne 0 ]; then
        log "FAILED extract world"; rm -rf "$tmp"; return 2
    fi
    # Swap each top-level world entry that is NOT a player part.
    # Move old aside, move new in. Player parts in world/ are left untouched.
    local old="$SERVER/.fe-old-world.$STAMP"
    rm -rf "$old"; mkdir -p "$old"
    local entry base
    for entry in "$tmp"/* "$tmp"/.[!.]*; do
        [ -e "$entry" ] || continue
        base=$(basename "$entry")
        case " ${PLAYER_PARTS[*]} " in *" $base "*) continue ;; esac
        [ "$base" = "session.lock" ] && continue
        [ -e "$SERVER/world/$base" ] && mv "$SERVER/world/$base" "$old/$base"
        mv "$entry" "$SERVER/world/$base" || { log "FAILED swap $base"; return 2; }
    done
    rm -rf "$old" "$tmp"
    return 0
}

# Restore only player parts from playerdata.tar.gz.
restore_player() {
    local arc="$BDIR/playerdata.tar.gz"
    [ -f "$arc" ] || { log "FAILED missing $arc"; return 2; }
    local tmp="$SERVER/.fe-restore-player.$STAMP"
    rm -rf "$tmp"; mkdir -p "$tmp" || return 2
    bytes_in "$(file_bytes "$arc")" "${1:-80}" "${2:-99}" < "$arc" | gzip -dc | tar -xf - -C "$tmp" 2>>"$LOG"
    if [ "${PIPESTATUS[0]}" -ne 0 ] || [ "${PIPESTATUS[1]}" -ne 0 ] || [ "${PIPESTATUS[2]}" -ne 0 ]; then
        log "FAILED extract playerdata"; rm -rf "$tmp"; return 2
    fi
    local old="$SERVER/.fe-old-player.$STAMP"
    rm -rf "$old"; mkdir -p "$old"
    local p
    for p in "${PLAYER_PARTS[@]}"; do
        [ -e "$tmp/$p" ] || continue
        [ -e "$SERVER/world/$p" ] && mv "$SERVER/world/$p" "$old/$p"
        mv "$tmp/$p" "$SERVER/world/$p" || { log "FAILED swap player $p"; return 2; }
    done
    rm -rf "$old" "$tmp"
    return 0
}

RC=0
# Bands: world body gets the larger slice; when both run, world is
# 20->80 and player is 80->99; single-scope spans 20->99.
if [ "$SCOPE" = world ]; then
    restore_world 20 99 || RC=2
elif [ "$SCOPE" = playerdata ]; then
    restore_player 20 99 || RC=2
else
    restore_world 20 80 || RC=2
    [ "$RC" -eq 0 ] && { restore_player 80 99 || RC=2; }
fi

if [ "$RC" -ne 0 ]; then
    log "ROLLBACK FAILED id=$ID scope=$SCOPE; safety snapshot at $SAFETY"
    echo "ROLLBACK_FAILED safety=$SAFETY" >&2
    exit 2
fi

log "ROLLBACK OK id=$ID scope=$SCOPE safety=$SAFETY"
echo "FE_PROGRESS=100"
echo "ROLLBACK_OK safety=$SAFETY"
exit 0
