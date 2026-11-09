#!/bin/bash
# S3 E2E: Pleos 에뮬레이터에서 실제 앱 + Virtual Console 시나리오 → 녹화·스크린샷·증거 로그
# 사용: tools/s3_e2e.sh <out_dir> [scenario]
set -u
OUT=${1:-qa/S3}; SC=${2:-probe}
mkdir -p "$OUT"
adb forward tcp:18088 tcp:8088 >/dev/null
python3 tools/vconsole.py gear Park >/dev/null && python3 tools/vconsole.py speed 0 >/dev/null
adb logcat -c
adb shell am start -S -n com.pikodrive/.MainActivity >/dev/null
sleep 5
adb shell screenrecord --time-limit 55 --bit-rate 4000000 /sdcard/e2e.mp4 &
REC=$!
sleep 1
adb exec-out screencap -p > "$OUT/01-parked.png"
python3 tools/vconsole.py scenario "$SC" > "$OUT/host.log" 2>&1 &
sleep 9;  adb exec-out screencap -p > "$OUT/02-driving.png"
sleep 13; adb exec-out screencap -p > "$OUT/03-after-brake.png"
sleep 3;  adb exec-out screencap -p > "$OUT/04-reverse.png"
sleep 9;  adb exec-out screencap -p > "$OUT/05-report.png"
wait $REC
adb pull /sdcard/e2e.mp4 "$OUT/e2e.mp4" >/dev/null 2>&1
adb logcat -d -v time -s 'PIKO_EVIDENCE:I' 'PikoGateway:*' 'PikoTts:*' > "$OUT/evidence.log"
adb logcat -d | grep -E "FATAL EXCEPTION" -A5 > "$OUT/crash.log" || true
echo "done → $OUT"
