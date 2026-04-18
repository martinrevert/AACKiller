#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 2 ]; then
  echo "Usage: $0 input.mkv output.mkv"
  exit 2
fi
in="$1"
out="$2"

ffmpeg -hide_banner -y -i "$in" -map 0 -c:v copy -c:s copy -c:a ac3 -b:a 192k -threads 2 "$out"
