#!/bin/bash
# 2-second tones in the formats people download sounds in (needs ffmpeg)
set -e
d="$1"; mkdir -p "$d"
tone() { ffmpeg -v error -y -f lavfi -i "sine=frequency=440:duration=2:sample_rate=$1" -af "volume=2.4" -ac "$2" "${@:3}"; }
tone 44100 1 -c:a libmp3lame -b:a 128k "$d/mono44.mp3"
tone 48000 2 -c:a libmp3lame -b:a 192k "$d/stereo48.mp3"
tone 22050 2 -c:a libvorbis "$d/stereo22.ogg"
tone 96000 2 -c:a pcm_s24le "$d/hires24.wav"
tone 8000 1 -c:a pcm_u8 "$d/old8bit.wav"
ls -la "$d"
