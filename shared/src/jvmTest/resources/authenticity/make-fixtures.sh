#!/usr/bin/env bash
# Regenerates the lossless-authenticity fixtures beside this script (LosslessAuthenticityFixturesTest).
#
#   FFMPEG=/path/to/ffmpeg ./make-fixtures.sh
#
# Any FFmpeg with libmp3lame does. The desktop app's own one is in the Gradle cache:
# unzip org/bytedeco/ffmpeg/windows-x86_64/* out of ffmpeg-7.1.1-1.5.12-windows-x86_64.jar.
#
# The source is 30 s of stereo noise reaching Nyquist (pink plus a little white, with a slow tremolo
# so the encoders have something to allocate bits to). Each fixture goes through the chain a fake
# goes through (encode lossy, decode, re-encode FLAC) and then out of FLAC as raw little-endian PCM:
# three 0.5 s windows (at 6 s, 15 s and 22.5 s) back to back, 44.1 kHz stereo.
set -euo pipefail

FFMPEG="${FFMPEG:-ffmpeg}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ff() { "$FFMPEG" -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i "anoisesrc=d=30:c=pink:r=44100:a=0.30:s=11" \
   -f lavfi -i "anoisesrc=d=30:c=pink:r=44100:a=0.30:s=12" \
   -f lavfi -i "anoisesrc=d=30:c=white:r=44100:a=0.03:s=13" \
   -f lavfi -i "anoisesrc=d=30:c=white:r=44100:a=0.03:s=14" \
   -filter_complex "[0][1]amerge=inputs=2[p];[2][3]amerge=inputs=2[w];[p][w]amix=inputs=2:normalize=0,tremolo=f=1.3:d=0.5" \
   -c:a pcm_f64le "$work/src.wav"

# The three windows, out of a FLAC, as raw PCM of the FLAC's own depth.
windows() { # in.flac out.raw s16le|s24le
    ff -i "$1" -filter_complex \
        "[0]atrim=6:6.5,asetpts=N/SR/TB[a];[0]atrim=15:15.5,asetpts=N/SR/TB[b];[0]atrim=22.5:23,asetpts=N/SR/TB[c];[a][b][c]concat=n=3:v=0:a=1" \
        -c:a "pcm_$3" -f "$3" "$2"
}
flac16() { ff -i "$1" -c:a flac -sample_fmt s16 "$2"; }
flac24() { ff -i "$1" -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 "$2"; }

flac16 "$work/src.wav" "$work/genuine-16.flac"
flac24 "$work/src.wav" "$work/genuine-24.flac"

ff -i "$work/src.wav" -c:a libmp3lame -b:a 128k "$work/mp3-128.mp3"
ff -i "$work/src.wav" -c:a libmp3lame -b:a 320k "$work/mp3-320.mp3"
ff -i "$work/src.wav" -c:a aac -b:a 128k "$work/aac-128.m4a"
flac16 "$work/mp3-128.mp3" "$work/mp3-128.flac"
flac16 "$work/mp3-320.mp3" "$work/mp3-320.flac"
flac16 "$work/aac-128.m4a" "$work/aac-128.flac"
# The usual fake hi-res: the MP3 decoded to 16 bits, then stored as a 24-bit FLAC.
ff -i "$work/mp3-128.mp3" -c:a pcm_s16le "$work/mp3-128-16.wav"
flac24 "$work/mp3-128-16.wav" "$work/mp3-128-24bit.flac"

for name in genuine-16 mp3-128 mp3-320 aac-128; do windows "$work/$name.flac" "$here/$name.s16le" s16le; done
for name in genuine-24 mp3-128-24bit; do windows "$work/$name.flac" "$here/$name.s24le" s24le; done
