#!/bin/bash
# ulac 帧序列 → mp4。用法: assemble.sh <framesDir> <fps> <out.mp4>
# 帧序列每 tick 一帧（fps=20 即 MC 实时速度）；everyN 录制时 fps=20/everyN。
set -e
DIR="${1:?用法: assemble.sh <framesDir> <fps> <out.mp4>}"
FPS="${2:-20}"
OUT="${3:-/tmp/opencode/ulac-demo.mp4}"
PATTERN="$DIR/frame_%05d.png"
[ -d "$DIR" ] || { echo "no such dir: $DIR"; exit 1; }
COUNT=$(ls "$DIR"/frame_*.png 2>/dev/null | wc -l)
[ "$COUNT" -gt 0 ] || { echo "no frames in $DIR"; exit 1; }
mkdir -p "$(dirname "$OUT")"
ffmpeg -y -loglevel error -framerate "$FPS" -i "$PATTERN" \
  -c:v libx264 -pix_fmt yuv420p -vf "scale=1280:720:flags=neighbor" \
  -movflags +faststart "$OUT"
echo "frames=$COUNT fps=$FPS -> $OUT ($(du -h "$OUT" | cut -f1))"
