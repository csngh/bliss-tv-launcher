#!/bin/sh
# Bakes a silky slow-motion (or sped-up) copy of a wallpaper clip.
#
# The launcher can play at 0.25x-2x, but a 30 fps clip slowed to 0.25x only has 7.5 different pictures a
# second, so motion looks stepped. This script uses motion interpolation (ffmpeg "minterpolate") to
# synthesise the in-between frames and writes a 60 fps H.264 file the launcher plays at 1x.
#
#   tools/smooth-slowmo.sh input.mp4 [speed] [output.mp4]
#
#   speed   1 = same speed, just add in-between frames (best for the pause-while-icons-visible
#           slow-down), 0.5 = half speed, 0.25 = four times slower, 2 = twice as fast (default 0.5)
#
# The result is capped at 30 seconds (the launcher's limit), 1080p max, no audio. Needs ffmpeg
# (brew install ffmpeg). Interpolation is slow: expect a minute or two per 30 s of output.
set -e
in="$1"; speed="${2:-0.5}"
[ -n "$in" ] || { echo "usage: $0 input.mp4 [speed] [output.mp4]"; exit 1; }
out="${3:-${in%.*}_${speed}x_60fps.mp4}"
command -v ffmpeg >/dev/null || { echo "ffmpeg not found (brew install ffmpeg)"; exit 1; }

# setpts stretches/squeezes time; minterpolate then fills it out to 60 fps with motion-compensated frames.
# Speeding up (speed > 1) needs no interpolation, only a frame-rate conversion.
if [ "$(echo "$speed <= 1" | bc -l)" = 1 ]; then
  filter="setpts=PTS/${speed},minterpolate=fps=60:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1"
else
  filter="setpts=PTS/${speed},fps=60"
fi
echo "Interpolating... this can take a few minutes."
ffmpeg -hide_banner -loglevel error -nostats -y -i "$in" -an \
  -vf "${filter},scale='min(1920,iw)':-2,format=yuv420p" -t 30 \
  -c:v libx264 -preset medium -crf 20 -movflags +faststart "$out"
echo "wrote $out"
