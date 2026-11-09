#!/bin/bash
# S5: 주행 중 앱 강제 종료 → 재실행 직후 터치 UI 노출 검사 (0.3초 간격 8회, 원시 시계열 기록)
set -u
S=${1:-qa/S5/restart}; mkdir -p "$S"
python3 tools/vconsole.py gear Drive >/dev/null; python3 tools/vconsole.py speed 50 >/dev/null; sleep 3
adb shell am force-stop --user current com.pikodrive; adb logcat -c
echo "launch $(date '+%H:%M:%S.%3N' 2>/dev/null || date '+%H:%M:%S')" > "$S/checks.log"
adb shell am start -n com.pikodrive/.MainActivity >/dev/null
leaks=0
for i in 1 2 3 4 5 6 7 8; do
  if python3 tools/ui.py has "쓰다듬기"; then r="LEAK"; leaks=$((leaks+1)); else r="ok"; fi
  echo "check $i $(python3 -c 'import time;print(time.strftime("%H:%M:%S")+".%03d"%(time.time()*1000%1000))') touch_ui=$r" >> "$S/checks.log"
  sleep 0.3
done
echo "leaks=$leaks" >> "$S/checks.log"
sleep 2; python3 tools/ui.py shot "$S/driving-after-restart.png"
python3 tools/vconsole.py speed 0 >/dev/null; python3 tools/vconsole.py gear Park >/dev/null; sleep 5
python3 tools/ui.py has "홈으로" && echo "after_park: F6 report shown" >> "$S/checks.log"
adb logcat -d -v time -s 'PIKO_EVIDENCE:I' > "$S/evidence.log"
python3 tools/ui.py tap "홈으로" >/dev/null 2>&1
cat "$S/checks.log"
