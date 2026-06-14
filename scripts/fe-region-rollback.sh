#!/bin/bash
#
# Father Eye region-selective rollback. Restores ONLY the chosen Anvil
# region files (.mca) of ONE dimension from a structured fe-backup.sh
# backup, leaving the rest of the live world untouched. This is the
# surgical complement to fe-rollback.sh: instead of swapping the whole
# world body, it swaps an operator-selected rectangle of 512x512-block
# region tiles (one .mca per 32x32 chunk region).
#
# SAFETY MODEL (mirrors fe-rollback.sh)
#   1. Refuses to run while the Minecraft server is up (a live RCON
#      answers on 25575). The caller (panel / web portal) also gates on
#      server-stopped; this is defence in depth.
#   2. Takes a pre-rollback safety snapshot of EXACTLY the .mca files it
#      is about to overwrite (plus any it is about to create), into
#      <DEST>/pre-region-rollback-<ts>/, so the operation is reversible.
#   3. Extracts the selected region files from world.tar.gz into a temp
#      dir on the SAME filesystem as the world, then atomically swaps
#      each file into place (move-old-aside, move-new-in, delete-old) so
#      an interrupted run never leaves a half-written region.
#
# DIMENSION -> region folder (matches DiskChunkRenderer.regionFolder):
#   minecraft:overworld           world/region
#   minecraft:the_nether          world/DIM-1/region
#   minecraft:the_end             world/DIM1/region
#   <ns>:<path> (modded)          world/dimensions/<ns>/<path>/region
#
# Usage:
#   fe-region-rollback.sh --id fe-YYYYMMDD-HHMMSS --dim <dimensionId>
#                         --regions "rx,rz rx,rz ..." [--server DIR] [--dest DIR]
#
#   --regions is a whitespace-separated list of REGION coordinates (NOT
#   chunk or block coordinates). One region covers chunks
#   [rx*32 .. rx*32+31] x [rz*32 .. rz*32+31].
#
# Exit codes:
#   0 success (all selected regions restored)
#   1 precondition failure (server up, backup missing, bad args, no world.tar.gz)
#   2 extraction/swap failure (live world left intact / partially restored;
#     the pre-rollback safety snapshot is reported for manual recovery)
#
set -u

SERVER="/Users/luke/Desktop/Server"
DEST="/Volumes/Server Backups/backups"
ID=""
DIM="minecraft:overworld"
REGIONS=""

while [ $# -gt 0 ]; do
    case "$1" in
        --id)         ID="${2:-}"; shift 2 ;;
        --id=*)       ID="${1#*=}"; shift ;;
        --dim)        DIM="${2:-}"; shift 2 ;;
        --dim=*)      DIM="${1#*=}"; shift ;;
        --regions)    REGIONS="${2:-}"; shift 2 ;;
        --regions=*)  REGIONS="${1#*=}"; shift ;;
        --server)     SERVER="${2:-}"; shift 2 ;;
        --server=*)   SERVER="${1#*=}"; shift ;;
        --dest)       DEST="${2:-}"; shift 2 ;;
        --dest=*)     DEST="${1#*=}"; shift ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
done

LOG="$DEST/backup.log"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" | tee -a "$LOG" >&2; }
echo "FE_PROGRESS=0"

# ---- argument validation --------------------------------------------------
BDIR="$DEST/$ID"
if [ -z "$ID" ] || [ ! -d "$BDIR" ]; then
    echo "FATAL: backup not found: $BDIR" >&2; exit 1
fi
ARC="$BDIR/world.tar.gz"
if [ ! -f "$ARC" ]; then
    echo "FATAL: backup has no world.tar.gz (region rollback needs world data): $ARC" >&2; exit 1
fi
if [ ! -d "$SERVER/world" ]; then
    echo "FATAL: world dir not found: $SERVER/world" >&2; exit 1
fi
if [ -z "${DIM// /}" ]; then
    echo "FATAL: --dim is required" >&2; exit 1
fi
if [ -z "${REGIONS// /}" ]; then
    echo "FATAL: --regions is required (e.g. \"0,0 0,1 1,0\")" >&2; exit 1
fi

# ---- dim -> world-relative region directory -------------------------------
# Emits the path of the region/ folder RELATIVE to $SERVER/world, matching
# the member-prefix layout inside world.tar.gz (which is rooted at world/).
dim_region_reldir() {
    local dim="$1" ns path
    case "$dim" in
        minecraft:overworld) echo "region" ;;
        minecraft:the_nether) echo "DIM-1/region" ;;
        minecraft:the_end) echo "DIM1/region" ;;
        *)
            # Modded dimension: namespace:path -> dimensions/<ns>/<path>/region
            ns="${dim%%:*}"
            path="${dim#*:}"
            if [ -z "$ns" ] || [ -z "$path" ] || [ "$ns" = "$dim" ]; then
                echo ""   # malformed id
            else
                echo "dimensions/$ns/$path/region"
            fi
            ;;
    esac
}

RELDIR="$(dim_region_reldir "$DIM")"
if [ -z "$RELDIR" ]; then
    echo "FATAL: cannot resolve region folder for dimension: $DIM" >&2; exit 1
fi

# ---- parse + validate region coordinates ----------------------------------
# Accept "rx,rz" tokens; reject anything non-integer so a malformed token
# can never widen the tar member-selection glob.
MCA_NAMES=()
for tok in $REGIONS; do
    rx="${tok%%,*}"
    rz="${tok#*,}"
    if [ "$rx" = "$tok" ] || [ -z "$rx" ] || [ -z "$rz" ]; then
        echo "FATAL: bad region token (want rx,rz): $tok" >&2; exit 1
    fi
    case "$rx" in ''|*[!0-9-]*) echo "FATAL: non-integer region x: $rx" >&2; exit 1 ;; esac
    case "$rz" in ''|*[!0-9-]*) echo "FATAL: non-integer region z: $rz" >&2; exit 1 ;; esac
    MCA_NAMES+=("r.$rx.$rz.mca")
done
if [ "${#MCA_NAMES[@]}" -eq 0 ]; then
    echo "FATAL: no valid regions parsed" >&2; exit 1
fi

# De-duplicate region names (operator may select overlapping boxes).
UNIQ_NAMES=()
for n in "${MCA_NAMES[@]}"; do
    dup=0
    for u in "${UNIQ_NAMES[@]:-}"; do [ "$u" = "$n" ] && { dup=1; break; }; done
    [ "$dup" -eq 0 ] && UNIQ_NAMES+=("$n")
done

log "REGION-ROLLBACK start id=$ID dim=$DIM reldir=$RELDIR regions=${UNIQ_NAMES[*]}"

# ---- server-must-be-stopped guard -----------------------------------------
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

STAMP=$(date +%Y%m%d-%H%M%S)
SAFETY="$DEST/pre-region-rollback-$STAMP"
LIVE_REGION_DIR="$SERVER/world/$RELDIR"
echo "FE_PROGRESS=10"

# ---- pre-rollback safety snapshot -----------------------------------------
# Snapshot exactly the live .mca files we are about to overwrite. Files that
# do not yet exist live (the backup would CREATE them) are recorded by name
# in created.txt so a manual undo knows to delete them.
mkdir -p "$SAFETY/$RELDIR" || { echo "FATAL: cannot create safety dir $SAFETY" >&2; exit 1; }
: > "$SAFETY/created.txt"
SNAP_COUNT=0
for name in "${UNIQ_NAMES[@]}"; do
    src="$LIVE_REGION_DIR/$name"
    if [ -f "$src" ]; then
        cp -p "$src" "$SAFETY/$RELDIR/$name" \
            || { log "FAILED safety snapshot $name"; rm -rf "$SAFETY"; exit 2; }
        SNAP_COUNT=$((SNAP_COUNT + 1))
    else
        echo "$RELDIR/$name" >> "$SAFETY/created.txt"
    fi
done
log "REGION-ROLLBACK safety snapshot complete ($SNAP_COUNT existing region(s) saved to $SAFETY)"
echo "FE_PROGRESS=25"

# ---- extract selected regions from world.tar.gz ---------------------------
# Pull ONLY the chosen members out of the archive. world.tar.gz members are
# rooted at "./" (tar -C world .), so each region is "./<reldir>/<name>".
TMP="$SERVER/.fe-region-restore.$STAMP"
rm -rf "$TMP"; mkdir -p "$TMP" || { echo "FATAL: cannot create temp dir $TMP" >&2; exit 2; }
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

MEMBERS=()
for name in "${UNIQ_NAMES[@]}"; do
    MEMBERS+=("./$RELDIR/$name")
done

# gzip -dc | tar -x with an explicit member list. Missing members make tar
# exit non-zero, which we tolerate per-file below (a region absent from the
# backup simply was not generated at backup time and is skipped).
gzip -dc < "$ARC" 2>>"$LOG" | tar -xf - -C "$TMP" "${MEMBERS[@]}" 2>>"$LOG"
# PIPESTATUS[0] is gzip; a corrupt archive is fatal. tar's status is allowed
# to be non-zero only because of absent members, which we re-check by file.
if [ "${PIPESTATUS[0]}" -ne 0 ]; then
    log "FAILED gunzip $ARC"; echo "REGION_ROLLBACK_FAILED safety=$SAFETY" >&2; exit 2
fi
echo "FE_PROGRESS=60"

# ---- atomic per-file swap -------------------------------------------------
mkdir -p "$LIVE_REGION_DIR" || { echo "FATAL: cannot create live region dir $LIVE_REGION_DIR" >&2; exit 2; }
OLD="$SERVER/.fe-old-region.$STAMP"
rm -rf "$OLD"; mkdir -p "$OLD"

RESTORED=0
SKIPPED=0
TOTAL="${#UNIQ_NAMES[@]}"
i=0
for name in "${UNIQ_NAMES[@]}"; do
    i=$((i + 1))
    extracted="$TMP/$RELDIR/$name"
    if [ ! -f "$extracted" ]; then
        # Region not present in the backup (was never generated then). Leave
        # the live file as-is and note it; this is not an error.
        log "SKIP region absent in backup: $RELDIR/$name"
        SKIPPED=$((SKIPPED + 1))
    else
        dst="$LIVE_REGION_DIR/$name"
        if [ -e "$dst" ]; then
            mv "$dst" "$OLD/$name" || { log "FAILED move-aside $name"; echo "REGION_ROLLBACK_FAILED safety=$SAFETY" >&2; exit 2; }
        fi
        if ! mv "$extracted" "$dst"; then
            # Roll the just-swapped file back so we never leave a hole.
            [ -e "$OLD/$name" ] && mv "$OLD/$name" "$dst" 2>>"$LOG"
            log "FAILED swap-in $name"; echo "REGION_ROLLBACK_FAILED safety=$SAFETY" >&2; exit 2
        fi
        RESTORED=$((RESTORED + 1))
    fi
    # Scale the per-file loop into the 60..99 band.
    pct=$((60 + (39 * i) / TOTAL))
    echo "FE_PROGRESS=$pct"
done

rm -rf "$OLD"
log "REGION-ROLLBACK OK id=$ID dim=$DIM restored=$RESTORED skipped=$SKIPPED safety=$SAFETY"
echo "FE_PROGRESS=100"
echo "REGION_ROLLBACK_OK restored=$RESTORED skipped=$SKIPPED safety=$SAFETY"
exit 0
