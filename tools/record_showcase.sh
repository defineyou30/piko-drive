#!/bin/bash
# 시연 영상 원본 녹화: 깨끗한 상태 → 첫 만남 → 데모 가속 → 얼굴 단계까지 사전 성장 → showcase 녹화
# 사용: tools/record_showcase.sh <out_dir>
set -u
S=${1:-qa/video}; mkdir -p "$S"
U() { python3 tools/ui.py "$@" >/dev/null 2>&1; }
V() { python3 tools/vconsole.py "$@" >/dev/null; }
adb shell pm clear --user current com.pikodrive >/dev/null
adb shell pm grant --user current com.pikodrive android.car.permission.CAR_SPEED
adb shell cmd uimode night no >/dev/null
V gear Park; V speed 0; V battery 64; V passenger out; V port closed
adb shell am start -n com.pikodrive/.MainActivity >/dev/null; sleep 6
U tap "같이 가자"; sleep 1; U tap "좋아, 출발하자"; sleep 1.5
U tap "설정"; sleep 1
adb shell input tap 2401 800; sleep 0.5      # 밤에는 은은하게: 끄기 (녹화 밝기 고정)
U tap "설정"; sleep 1
U tap "피코 ·" 5; sleep 1; U tap "켜기"; sleep 4   # 데모 가속 (세션 재시작)
python3 tools/vconsole.py scenario pregrow >/dev/null; sleep 2
U tap "홈으로"
sleep 125   # 사전 주행의 출발 인사 쿨다운(2분)이 풀리길 기다린다 → 본편에서 출발 인사가 나오게
adb logcat -c
adb shell screenrecord --time-limit 140 --bit-rate 6000000 /sdcard/showcase.mp4 &
REC=$!
sleep 1
python3 tools/vconsole.py scenario showcase > "$S/host.log" 2>&1
sleep 8
U tap "홈으로"; sleep 4
kill -INT $REC 2>/dev/null; sleep 3
adb pull /sdcard/showcase.mp4 "$S/showcase.mp4" >/dev/null
adb logcat -d -v time -s 'PIKO_EVIDENCE:I' > "$S/evidence.log"
grep -E "speech|event|growth|trip" "$S/evidence.log" | sed 's/^10-24 //'
