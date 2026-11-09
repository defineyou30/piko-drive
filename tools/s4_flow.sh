#!/bin/bash
# S4 화면 흐름 QA: 첫 실행 → 홈 → 쓰다듬기 → 설정 → 데모 패널 → 주행 → 도착 리포트 → 리포트 재열람
# 사용: tools/s4_flow.sh <out_dir>   (Pleos 에뮬레이터 + adb forward tcp:18088 tcp:8088)
set -u
S=${1:-qa/S4}; mkdir -p "$S"
U() { python3 tools/ui.py "$@"; }
adb shell pm clear --user current com.pikodrive >/dev/null
adb shell pm grant --user current com.pikodrive android.car.permission.CAR_SPEED
python3 tools/vconsole.py gear Park >/dev/null; python3 tools/vconsole.py speed 0 >/dev/null
adb logcat -c
adb shell am start -n com.pikodrive/.MainActivity >/dev/null; sleep 5
U shot "$S/01-F0.png"
U tap "같이 가자"; sleep 1; U shot "$S/02-F0b.png"
U tap "좋아, 출발하자"; sleep 1.5; U shot "$S/03-F1-first.png"
U tap "쓰다듬기"; sleep 0.8; U shot "$S/04-F1-pet.png"
U tap "설정"; sleep 1; U shot "$S/05-F8.png"
U tap "끄기"; sleep 0.8; U shot "$S/06-F8-voiceoff.png"
U tap "켜기"; sleep 0.5
U tap "설정"; sleep 1          # 뒤로 (헤더 '설정' 탭)
U tap "피코 ·" 5; sleep 1; U shot "$S/07-F9.png"
U tap "데모 패널"; sleep 1      # 뒤로
python3 tools/vconsole.py scenario probe > "$S/host.log" 2>&1 &
sleep 10; U shot "$S/08-driving.png"
U has "쓰다듬기" && echo "FAIL: touch UI visible while driving" || echo "OK: no touch UI while driving"
sleep 24; U shot "$S/09-F6.png"
U tap "홈으로"; sleep 1.5; U shot "$S/10-F1-after.png"
U tap "리포트"; sleep 1; U shot "$S/11-report-reopen.png"
U tap "홈으로"; sleep 1
adb logcat -d -v time -s 'PIKO_EVIDENCE:I' > "$S/evidence.log"
adb logcat -d | grep -E "FATAL EXCEPTION" -A6 > "$S/crash.log" || true
echo "done → $S"
