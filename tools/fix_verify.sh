#!/bin/bash
# 기능 평가 결함(D1~D3) 재검증
V() { python3 tools/vconsole.py "$@" >/dev/null; }
EV() { adb logcat -d -s PIKO_EVIDENCE:I | grep -E "event|trip|speech|mode" | sed 's/^.*PIKO_EVIDENCE: //'; }
echo "== D2/D3 idle D 20s at 0km/h then P (expect: no Departed, no trip, no speech)"
adb logcat -c; V gear Drive; sleep 20; V gear Park; sleep 5; EV
echo "== D3 reverse-only maneuver (expect: no trip)"
adb logcat -c; V gear Reverse; V speed 5; sleep 3; V speed 0; V gear Park; sleep 5; EV
echo "== real short drive (expect: Departed after ~3s moving, trip report)"
adb logcat -c; V gear Drive; V speed 30; sleep 8; V speed 0; V gear Park; sleep 5; EV
python3 tools/ui.py tap "홈으로" >/dev/null 2>&1
echo "== D1 background: open Settings app during drive, hard brake, park (expect HardBrake + trip at real time)"
adb logcat -c; V gear Drive; V speed 50; sleep 5
adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 25
adb shell dumpsys activity processes | grep -A3 "com.pikodrive" | grep -o "isFrozen=[a-z]*" | head -1
python3 tools/vconsole.py scenario reverse-during-speech >/dev/null 2>&1 || true
V speed 50; sleep 2; for s in 40 30 20 15; do V speed $s; sleep 0.4; done; sleep 4; V speed 0; V gear Park; sleep 6; EV
adb shell am start -n com.pikodrive/.MainActivity >/dev/null
