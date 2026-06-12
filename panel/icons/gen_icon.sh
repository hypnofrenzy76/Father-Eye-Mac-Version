#!/bin/sh
# Regenerate fathereye.icns from gen_icon.py. Run on macOS (sips + iconutil).
set -e
cd "$(dirname "$0")"
python3 gen_icon.py fathereye-1024.png
rm -rf fathereye.iconset
mkdir fathereye.iconset
for s in 16 32 128 256 512; do
    sips -z $s $s fathereye-1024.png --out "fathereye.iconset/icon_${s}x${s}.png" >/dev/null
    d=$((s * 2))
    sips -z $d $d fathereye-1024.png --out "fathereye.iconset/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns fathereye.iconset -o fathereye.icns
rm -rf fathereye.iconset
echo "wrote fathereye.icns"
