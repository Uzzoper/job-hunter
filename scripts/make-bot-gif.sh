#!/usr/bin/env bash
# make-bot-gif.sh
#
# Converts a screen recording of the Hermes Desktop app into the README demo
# GIF (docs/assets/bot-demo.gif), palette-optimized so it stays small enough to
# load inline on GitHub.
#
# Usage:
#   bash scripts/make-bot-gif.sh <input-video> [output.gif]
#
# Examples:
#   bash scripts/make-bot-gif.sh ~/Videos/bot-demo.mp4
#   WIDTH=1100 FPS=15 bash scripts/make-bot-gif.sh ~/Videos/bot-demo.mkv
#
# Environment overrides:
#   WIDTH   output width in pixels, height keeps aspect ratio  (default 900)
#   FPS     frame rate of the GIF                              (default 12)
#   MAX_MB  warn above this size, GitHub inline limit is ~10MB (default 5)
#
# On X11 (this project's dev machines) record with OBS "Window Capture" on the
# Hermes Desktop window, or directly:
#   ffmpeg -f x11grab -framerate 30 -video_size 1280x720 -i :0.0+0,0 demo.mp4
set -euo pipefail

if [[ $# -lt 1 ]]; then
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi

INPUT="$1"
OUTPUT="${2:-docs/assets/bot-demo.gif}"
WIDTH="${WIDTH:-900}"
FPS="${FPS:-12}"
MAX_MB="${MAX_MB:-5}"

if [[ ! -f "$INPUT" ]]; then
  echo "error: input file not found: $INPUT" >&2
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "error: ffmpeg not found in PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"

echo "==> $INPUT -> $OUTPUT (width=${WIDTH}px, fps=${FPS})"

# Two-pass palette: a global 256-color table beats ffmpeg's default per-frame
# palette on UI screenshots (text stays readable, flat backgrounds do not dither).
ffmpeg -hide_banner -loglevel error -y -i "$INPUT" \
  -vf "fps=${FPS},scale=${WIDTH}:-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  -loop 0 "$OUTPUT"

SIZE_KB=$(( $(stat -c%s "$OUTPUT") / 1024 ))
echo "==> done: $OUTPUT (${SIZE_KB} KB)"

if (( SIZE_KB > MAX_MB * 1024 )); then
  echo "warning: ${SIZE_KB} KB exceeds ${MAX_MB} MB — GitHub caps inline media at ~10 MB." >&2
  echo "         retry with a shorter clip, or: WIDTH=720 FPS=10 bash $0 \"$INPUT\"" >&2
  exit 2
fi
