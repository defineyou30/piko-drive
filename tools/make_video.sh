#!/bin/bash
# 시연 영상 합성: 제목 카드 → 실측 녹화(자막) → 안전 수치 카드
# 원본: qa/S5/release-demo90/demo90.mp4 (제출 release APK, Pleos 에뮬레이터 + Virtual Console)
set -eu
SRC=${1:-qa/S5/release-demo90/demo90.mp4}
OUT=${2:-submission/video/피코드라이브_시연영상.mp4}
FONT=app/src/main/res/font/pretendard_semibold.otf
FONTB=app/src/main/res/font/pretendard_bold.otf
TMP=$(mktemp -d)
BG=0x14110F
AMBER=0xFFB547
WHITE=0xF4EFE6
GRAY=0xA39A90

# 피코(몸 단계) 이미지: 리포트 화면에서 잘라 쓴다 (앱이 그린 캐릭터, AI 생성 아님)
ffmpeg -loglevel error -y -i qa/S4/demo90-run3/report-crop.png -vf "crop=700:1000:20:120,scale=-1:520" "$TMP/piko.png"

card() { # $1=out $2=dur $3..=drawtext filters
  local out=$1 dur=$2; shift 2
  ffmpeg -loglevel error -y -f lavfi -i "color=c=$BG:s=1920x1080:d=$dur:r=30" -f lavfi -i anullsrc=r=48000:cl=stereo \
    -vf "$*" -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac "$out"
}

# 1) 제목 카드
ffmpeg -loglevel error -y -f lavfi -i "color=c=$BG:s=1920x1080:d=5:r=30" -i "$TMP/piko.png" -f lavfi -i anullsrc=r=48000:cl=stereo \
  -filter_complex "[0][1]overlay=x=260:y=(H-h)/2,drawtext=fontfile=$FONTB:text='피코 드라이브':fontcolor=$WHITE:fontsize=96:x=820:y=380,drawtext=fontfile=$FONT:text='내 운전이 펫의 기분이 되는 차량 전용 동승 펫':fontcolor=$GRAY:fontsize=40:x=824:y=510,drawtext=fontfile=$FONT:text='“한 픽셀씩, 함께 가요.”':fontcolor=$AMBER:fontsize=44:x=824:y=590" \
  -shortest -t 5 -c:v libx264 -pix_fmt yuv420p -c:a aac "$TMP/01.mp4"

# 2) 촬영 조건 카드 (실측임을 명시)
card "$TMP/02.mp4" 5 "drawtext=fontfile=$FONTB:text='이 영상은 실제 앱 화면 녹화입니다':fontcolor=$WHITE:fontsize=56:x=(w-tw)/2:y=360,drawtext=fontfile=$FONT:text='Pleos Connect 에뮬레이터 v2.1.0 · 공식 Virtual Console로 속도·기어 주입':fontcolor=$GRAY:fontsize=36:x=(w-tw)/2:y=470,drawtext=fontfile=$FONT:text='시연용 성장 가속(×360) 사용 · 피코의 음성 대사는 자막으로 표시':fontcolor=$GRAY:fontsize=36:x=(w-tw)/2:y=530,drawtext=fontfile=$FONT:text='제출 release APK SHA-256 85623d0c…':fontcolor=0x6F675F:fontsize=28:x=(w-tw)/2:y=640"

# 3) 본편: 자막 구간 (초, 녹화 기준)
cap() { # start end text [color]
  echo "drawtext=fontfile=$FONT:text='$3':fontcolor=${4:-$WHITE}:fontsize=40:x=(w-tw)/2:y=h-120:box=1:boxcolor=black@0.62:boxborderw=22:enable='between(t,$1,$2)'"
}
F="scale=1920:1080"
F="$F,drawtext=fontfile=$FONT:text='실측 · Pleos 에뮬레이터 + Virtual Console':fontcolor=$AMBER:fontsize=26:x=40:y=1000:box=1:boxcolor=black@0.5:boxborderw=10"
F="$F,$(cap 0 5 '정차(P) — 정차 중에만 피코와 놀 수 있어요')"
F="$F,$(cap 5 12 '출발(D) — 버튼·글자가 사라지고 피코는 말로만  “출발! 같이 가자.”')"
F="$F,$(cap 12 35 '부드럽게 달릴수록 아래 픽셀 바가 채워져요')"
F="$F,$(cap 35 44 '편안한 주행이 이어지면 칭찬  “흔들림 없어서 포근해.”')"
F="$F,$(cap 44 68 '주행 화면에는 피코와 픽셀 바만 · 표정·발화 횟수 제한은 테스트로 검증')"
F="$F,$(cap 68 72 '급제동 감지 — 놀란 운전자에게 바로 말 걸지 않고…')"
F="$F,$(cap 72 77.5 '2.5초 뒤  “앗, 깜짝 놀랐어!”  (운전자를 탓하지 않아요)' $AMBER)"
F="$F,$(cap 77.5 84 '후진(R) — 화면 정지·무음 (말하던 중이어도 0.4초 안에 멈춤)')"
F="$F,$(cap 84 100 '정차(P) — 쌓인 픽셀이 한 칸씩 켜지며 성장 → 도착 리포트')"
ffmpeg -loglevel error -y -i "$SRC" -f lavfi -i anullsrc=r=48000:cl=stereo -vf "$F" -r 30 -shortest -c:v libx264 -crf 20 -pix_fmt yuv420p -c:a aac "$TMP/03.mp4"

# 4) 수치 카드
card "$TMP/04.mp4" 7 "drawtext=fontfile=$FONTB:text='에뮬레이터 실측':fontcolor=$WHITE:fontsize=56:x=200:y=220,drawtext=fontfile=$FONT:text='급제동 판정 6패턴 정확 · 오탐 0':fontcolor=$WHITE:fontsize=44:x=200:y=360,drawtext=fontfile=$FONT:text='발화 중 후진 → 무음 0.36초 이내':fontcolor=$WHITE:fontsize=44:x=200:y=440,drawtext=fontfile=$FONT:text='주행 중 터치 UI 노출 0':fontcolor=$WHITE:fontsize=44:x=200:y=520,drawtext=fontfile=$FONT:text='Pleos Vehicle SDK · Pleos TTS SDK · AAOS Car API':fontcolor=$AMBER:fontsize=36:x=200:y=640,drawtext=fontfile=$FONT:text='근거 — GitHub 저장소 qa/EVIDENCE.md':fontcolor=$GRAY:fontsize=30:x=200:y=720"

printf "file '%s'\n" "$TMP/01.mp4" "$TMP/02.mp4" "$TMP/03.mp4" "$TMP/04.mp4" > "$TMP/list.txt"
ffmpeg -loglevel error -y -f concat -safe 0 -i "$TMP/list.txt" -c copy "$OUT"
rm -rf "$TMP"
ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$OUT"
