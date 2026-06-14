#!/bin/bash
#
# Father Eye structured compressed backup.
#
# Produces a self-describing backup directory on the external drive:
#
#   <DEST>/fe-<timestamp>/
#       world.tar.gz        full world MINUS the player-owned subtrees
#       playerdata.tar.gz   only the player-owned subtrees (playerdata,
#                           playermap, stats, advancements, deaths, arcanum)
#       server.tar.gz       server.properties, config, scripts, ops/whitelist/ban lists
#       manifest.json       metadata: timestamps, component sizes, label, world name
#
# Splitting world and player data into separate archives is what lets the
# Father Eye rollback restore world and player data INDEPENDENTLY.
#
# All archives stream tar -> gzip directly to the external drive; no
# uncompressed intermediate is ever written to the internal disk.
#
# Usage:
#   fe-backup.sh [--label "text"] [--server DIR] [--dest DIR] [--no-rcon]
#
# Exit codes:
#   0  success
#   1  configuration / precondition failure (drive missing, no server dir)
#   2  archive failure (a tar/gzip step failed); partial output removed
#
set -u
set -o pipefail

# ---- defaults (overridable by flags) ----------------------------------
SERVER="/Users/luke/Desktop/Server"
DEST="/Volumes/Server Backups/backups"
LABEL=""
USE_RCON=1
MIN_FREE_GB=60
RETAIN_DAYS=14

# ---- arg parsing ------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --label)     LABEL="${2:-}"; shift 2 ;;
        --label=*)   LABEL="${1#*=}"; shift ;;
        --server)    SERVER="${2:-}"; shift 2 ;;
        --server=*)  SERVER="${1#*=}"; shift ;;
        --dest)      DEST="${2:-}"; shift 2 ;;
        --dest=*)    DEST="${1#*=}"; shift ;;
        --no-rcon)   USE_RCON=0; shift ;;
        --retain-days) RETAIN_DAYS="${2:-14}"; shift 2 ;;
        --retain-days=*) RETAIN_DAYS="${1#*=}"; shift ;;
        --min-free-gb) MIN_FREE_GB="${2:-60}"; shift 2 ;;
        --min-free-gb=*) MIN_FREE_GB="${1#*=}"; shift ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
done

# DEST may be passed as the parent (.../backups); derive the external root
# for the free-space log too. We back up INTO $DEST.
EXTROOT="$DEST"
LOG="$DEST/backup.log"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" | tee -a "$LOG" >&2; }

# ---- preconditions ----------------------------------------------------
if [ ! -d "$SERVER" ]; then
    echo "FATAL: server dir not found: $SERVER" >&2
    exit 1
fi
if [ ! -d "$SERVER/world" ]; then
    echo "FATAL: world dir not found: $SERVER/world" >&2
    exit 1
fi
# The external volume must be mounted. Check the mount point (parent of
# the backups dir), not just the backups dir, so we never silently write
# to an unmounted-volume stub directory on the internal disk.
DEST_PARENT="$(dirname "$DEST")"
if [ ! -d "$DEST_PARENT" ]; then
    echo "FATAL: backup destination volume not mounted: $DEST_PARENT" >&2
    exit 1
fi
mkdir -p "$DEST" || { echo "FATAL: cannot create $DEST" >&2; exit 1; }

# ---- rcon helper (save bracketing) ------------------------------------
rcon() {
python3 - "$1" <<'EOF'
import socket, struct, sys
try:
    pw = open('/Users/luke/Desktop/Server/.rcon_pw').read().strip()
except Exception as e:
    print('RCON_FAIL no-pw %s' % e); sys.exit(2)
def pkt(rid, ptype, body):
    d = struct.pack('<ii', rid, ptype) + body.encode() + b'\x00\x00'
    return struct.pack('<i', len(d)) + d
def recv(s):
    ln = struct.unpack('<i', s.recv(4))[0]
    d = b''
    while len(d) < ln:
        chunk = s.recv(ln - len(d))
        if not chunk: break
        d += chunk
    return d[8:-2].decode(errors='replace')
try:
    s = socket.create_connection(('127.0.0.1', 25575), timeout=10)
    s.sendall(pkt(1, 3, pw)); recv(s)
    s.sendall(pkt(2, 2, sys.argv[1])); print(recv(s))
    s.close()
except Exception as e:
    print('RCON_FAIL %s' % e); sys.exit(2)
EOF
}

STAMP=$(date +%Y%m%d-%H%M%S)
BDIR="$DEST/fe-$STAMP"
PARTIAL="$BDIR.partial"
rm -rf "$PARTIAL"
mkdir -p "$PARTIAL" || { echo "FATAL: cannot create $PARTIAL" >&2; exit 1; }

# ---- save-off / flush bracket -----------------------------------------
SAVED_OFF=0
if [ "$USE_RCON" = 1 ]; then
    if rcon "save-off" | grep -qv RCON_FAIL; then
        SAVED_OFF=1
        rcon "save-all flush" > /dev/null 2>&1
        sleep 5
    else
        log "WARN rcon save-off unavailable; backing up live world without save bracket"
    fi
fi
restore_saves() { [ "$SAVED_OFF" = 1 ] && rcon "save-on" > /dev/null 2>&1; SAVED_OFF=0; }
# Ensure save-on always runs even on an interrupt.
trap 'restore_saves' EXIT

# ---- player-owned subtrees (relative to world/) -----------------------
PLAYER_PARTS=(playerdata playermap stats advancements deaths arcanum)

# Build tar --exclude args so world.tar.gz omits the player parts.
WORLD_EXCLUDES=()
for p in "${PLAYER_PARTS[@]}"; do
    WORLD_EXCLUDES+=(--exclude "./$p")
done

# session.lock is held by the running server; never archive it.
COMMON_EXCLUDES=(--exclude "./session.lock" --exclude "*.lock" --exclude ".DS_Store")

fail() {
    log "FAILED $1"
    restore_saves
    rm -rf "$PARTIAL"
    exit 2
}

# ---- world.tar.gz (world minus player parts) --------------------------
log "BACKUP start $BDIR label='${LABEL}'"
nice -n 19 tar -cf - -C "$SERVER/world" "${COMMON_EXCLUDES[@]}" "${WORLD_EXCLUDES[@]}" . 2>>"$LOG" \
    | nice -n 19 gzip -1 > "$PARTIAL/world.tar.gz"
WPS=("${PIPESTATUS[@]}")
[ "${WPS[0]}" -eq 0 ] && [ "${WPS[1]}" -eq 0 ] && [ -s "$PARTIAL/world.tar.gz" ] || fail "world.tar.gz"

# ---- playerdata.tar.gz (only the player parts that exist) -------------
PRESENT_PARTS=()
for p in "${PLAYER_PARTS[@]}"; do
    [ -e "$SERVER/world/$p" ] && PRESENT_PARTS+=("$p")
done
if [ "${#PRESENT_PARTS[@]}" -gt 0 ]; then
    nice -n 19 tar -cf - -C "$SERVER/world" "${COMMON_EXCLUDES[@]}" "${PRESENT_PARTS[@]}" 2>>"$LOG" \
        | nice -n 19 gzip -1 > "$PARTIAL/playerdata.tar.gz"
    PPS=("${PIPESTATUS[@]}")
    [ "${PPS[0]}" -eq 0 ] && [ "${PPS[1]}" -eq 0 ] && [ -s "$PARTIAL/playerdata.tar.gz" ] || fail "playerdata.tar.gz"
else
    log "WARN no player-owned subtrees present; playerdata.tar.gz skipped"
fi

# ---- server.tar.gz (config + properties + scripts + lists) ------------
SERVER_PARTS=()
for f in server.properties ops.json whitelist.json banned-players.json banned-ips.json config scripts defaultconfigs; do
    [ -e "$SERVER/$f" ] && SERVER_PARTS+=("$f")
done
if [ "${#SERVER_PARTS[@]}" -gt 0 ]; then
    nice -n 19 tar -cf - -C "$SERVER" "${COMMON_EXCLUDES[@]}" "${SERVER_PARTS[@]}" 2>>"$LOG" \
        | nice -n 19 gzip -1 > "$PARTIAL/server.tar.gz"
    SPS=("${PIPESTATUS[@]}")
    [ "${SPS[0]}" -eq 0 ] && [ "${SPS[1]}" -eq 0 ] && [ -s "$PARTIAL/server.tar.gz" ] || fail "server.tar.gz"
fi

# restore saves now that all reads are done.
restore_saves
trap - EXIT

# ---- manifest.json ----------------------------------------------------
sz() { [ -f "$1" ] && stat -f '%z' "$1" 2>/dev/null || echo 0; }
WORLD_SZ=$(sz "$PARTIAL/world.tar.gz")
PLAYER_SZ=$(sz "$PARTIAL/playerdata.tar.gz")
SERVER_SZ=$(sz "$PARTIAL/server.tar.gz")
WORLD_NAME=$(grep -E '^level-name=' "$SERVER/server.properties" 2>/dev/null | head -1 | cut -d= -f2)
[ -z "$WORLD_NAME" ] && WORLD_NAME="world"
ISO_NOW=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
EPOCH_NOW=$(date '+%s')

# JSON-escape the label and world name (minimal: backslash and double-quote;
# strip any stray CR/LF so a weird level-name can't break the JSON).
json_escape() { printf '%s' "$1" | tr -d '\r\n' | sed 's/\\/\\\\/g; s/"/\\"/g'; }
ESC_LABEL=$(json_escape "$LABEL")
ESC_WORLD_NAME=$(json_escape "$WORLD_NAME")

cat > "$PARTIAL/manifest.json" <<JSON
{
  "schema": 1,
  "id": "fe-$STAMP",
  "createdIso": "$ISO_NOW",
  "createdEpoch": $EPOCH_NOW,
  "label": "$ESC_LABEL",
  "worldName": "$ESC_WORLD_NAME",
  "components": {
    "world": { "file": "world.tar.gz", "bytes": $WORLD_SZ, "present": $([ "$WORLD_SZ" -gt 0 ] && echo true || echo false) },
    "playerdata": { "file": "playerdata.tar.gz", "bytes": $PLAYER_SZ, "present": $([ "$PLAYER_SZ" -gt 0 ] && echo true || echo false) },
    "server": { "file": "server.tar.gz", "bytes": $SERVER_SZ, "present": $([ "$SERVER_SZ" -gt 0 ] && echo true || echo false) }
  },
  "totalBytes": $((WORLD_SZ + PLAYER_SZ + SERVER_SZ))
}
JSON

# ---- atomic publish ---------------------------------------------------
mv "$PARTIAL" "$BDIR" || fail "publish-rename"
TOTAL_H=$(du -sh "$BDIR" 2>/dev/null | awk '{print $1}')
log "OK $BDIR total=$TOTAL_H world=$WORLD_SZ player=$PLAYER_SZ server=$SERVER_SZ"

# ---- retention: age, then free-space ----------------------------------
# Age-based: delete fe-* backups older than RETAIN_DAYS.
if [ "$RETAIN_DAYS" -gt 0 ]; then
    CUTOFF=$(( EPOCH_NOW - RETAIN_DAYS * 86400 ))
    for d in "$DEST"/fe-*; do
        [ -d "$d" ] || continue
        m="$d/manifest.json"
        ts=0
        if [ -f "$m" ]; then
            ts=$(grep -E '"createdEpoch"' "$m" | grep -oE '[0-9]+' | head -1)
        fi
        [ -z "$ts" ] && ts=0
        if [ "$ts" -gt 0 ] && [ "$ts" -lt "$CUTOFF" ]; then
            rm -rf "$d" && log "PRUNED-AGE $d"
        fi
    done
fi

# Free-space-based: while under MIN_FREE_GB, delete the oldest fe-* dir
# (keep at least one). Also prunes legacy thaumaturgy-*.tar.gz bundles.
while true; do
    FREE=$(df -g "$DEST" | awk 'NR==2{print $4}')
    [ -z "$FREE" ] && break
    [ "$FREE" -ge "$MIN_FREE_GB" ] && break
    OLDEST=$(ls -1dt "$DEST"/fe-* "$DEST"/thaumaturgy-*.tar.gz 2>/dev/null | tail -1)
    [ -z "$OLDEST" ] && break
    COUNT=$(ls -1d "$DEST"/fe-* 2>/dev/null | wc -l | tr -d ' ')
    [ "$COUNT" -le 1 ] && [ -z "$(ls -1 "$DEST"/thaumaturgy-*.tar.gz 2>/dev/null)" ] && break
    rm -rf "$OLDEST" && log "PRUNED-SPACE $OLDEST (free ${FREE}GB)"
done

# Emit the backup path on stdout with an unambiguous, machine-parseable
# marker. All human logging goes to stderr via log(); callers that capture
# stdout should grep for the FE_BACKUP_DIR= line rather than assume the
# last line, since stderr/stdout interleave order is not guaranteed once a
# parent merges the two streams.
echo "FE_BACKUP_DIR=$BDIR"
echo "$BDIR"
exit 0
