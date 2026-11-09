#!/bin/bash
# S4 게이트: 실차 데이터(Pleos+Virtual Console) + F9 데모 모드로 90초 데모 재현. 녹화+판정
# 사용: tools/s4_demo90.sh <out_dir>
set -u
S=${1:-qa/S4/demo90}; mkdir -p "$S"
U() { python3 tools/ui.py "$@"; }
adb shell pm clear --user current com.pikodrive >/dev/null
adb shell pm grant --user current com.pikodrive android.car.permission.CAR_SPEED
python3 tools/vconsole.py gear Park >/dev/null; python3 tools/vconsole.py speed 0 >/dev/null
adb shell am start -n com.pikodrive/.MainActivity >/dev/null; sleep 5
U tap "같이 가자" >/dev/null; sleep 0.8; U tap "좋아, 출발하자" >/dev/null; sleep 1.2
U tap "피코 ·" 5 >/dev/null; sleep 1; U tap "켜기" >/dev/null; sleep 2  # 데모 스위치는 세션을 재시작해 홈으로 돌아간다
adb logcat -c
adb shell screenrecord --time-limit 100 --bit-rate 4000000 /sdcard/demo90.mp4 &
REC=$!
python3 tools/vconsole.py scenario demo90 > "$S/host.log" 2>&1
sleep 6; U shot "$S/report.png"
wait $REC
adb pull /sdcard/demo90.mp4 "$S/demo90.mp4" >/dev/null 2>&1
adb logcat -d -v time -s 'PIKO_EVIDENCE:I' > "$S/evidence.log"
adb logcat -d | grep -E "FATAL EXCEPTION" -A6 > "$S/crash.log" || true
# 판정
ok=1
for need in "SPOKEN GREETING" "SPOKEN COMFORT" "SPOKEN STARTLE" "SPOKEN ARRIVAL" "mode REVERSING" "event HardBrake"; do
  grep -q "$need" "$S/evidence.log" || { echo "MISSING: $need"; ok=0; }
done
grep -E "speech .*(REVERSING)" "$S/evidence.log" && ok=0
[ -s "$S/crash.log" ] && { echo "CRASH"; ok=0; }
grep "trip " "$S/evidence.log" | tail -1
# 어떤 빌드로 돌렸는지 고정 (APK 경로·해시·버전)
APK=$(adb shell pm path --user current com.pikodrive | head -1 | sed 's/package://' | tr -d '\r')
adb pull "$APK" "$S/installed.apk" >/dev/null 2>&1
{
  echo "date: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "installed_apk_sha256: $(shasum -a 256 "$S/installed.apk" | cut -d' ' -f1)"
  adb shell dumpsys package com.pikodrive | grep -m2 -E "versionName|versionCode" | sed 's/^ *//'
  echo "checks: SPOKEN GREETING/COMFORT/STARTLE/ARRIVAL, mode REVERSING, event HardBrake, no speech during REVERSING, no crash"
  [ $ok = 1 ] && echo "verdict: PASS" || echo "verdict: FAIL"
} > "$S/verdict.log"
rm -f "$S/installed.apk"
[ $ok = 1 ] && echo "PASS" || echo "FAIL"
