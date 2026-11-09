#!/usr/bin/env python3
"""
v4 시연 영상 원본 녹화 (Pleos 에뮬레이터, 디버그 APK).
  adb forward tcp:18088 tcp:8088
  python3 tools/record_v4.py qa/video4
결과: a_input.mp4(설정·입력), b_ride.mp4(뒷좌석 루틴), c_rear.webm(뒷좌석 화면), marks.json(장면 표식의 녹화 기준 초),
      guardian.jsonl, evidence.log
- 앞 화면은 adb screenrecord(최대 180초)라 두 번에 나눠 찍는다.
- 뒷좌석 화면은 보조 디스플레이를 붙이면 몇 분 안에 에뮬레이터 시스템이 불안정해지므로(qa/v4/README.md) 마지막에 짧게.
- 벨트는 콘솔 주입 경로가 없어 디버그 대체 입력 (화면에 "시뮬레이션 입력" 배지).
"""
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
import vconsole  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "qa/video4"
os.makedirs(OUT, exist_ok=True)
PKG = "com.pikodrive"
marks = {}


def ui(*a):
    return subprocess.run(["python3", "tools/ui.py", *a], capture_output=True).returncode


def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout


def user():
    return sh("am", "get-current-user").strip()


def tap_until(text, expect, tries=8):
    for _ in range(tries):
        ui("tap", text)
        time.sleep(1.2)
        if ui("has", expect) == 0:
            return True
    return False


class Rec:
    """adb screenrecord 한 토막. t0 = 녹화 시작 시각(호스트 epoch)"""

    def __init__(self, name):
        self.name = name
        self.remote = f"/sdcard/{name}.mp4"
        self.p = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "180", "--bit-rate", "8000000", self.remote])
        time.sleep(0.6)
        self.t0 = time.time()

    def mark(self, label):
        marks.setdefault(self.name, {})[label] = round(time.time() - self.t0, 2)
        print(f"[{self.name} {marks[self.name][label]:6.2f}s] {label}", flush=True)

    def stop(self):
        sh("pkill", "-INT", "screenrecord")
        self.p.wait(timeout=20)
        time.sleep(1.5)
        subprocess.run(["adb", "pull", self.remote, f"{OUT}/{self.name}.mp4"], capture_output=True)


def voice(phrase):
    pcm = f"{OUT}/.v.pcm"
    subprocess.run(["say", "-v", "Yuna", "-r", "150", "-o", f"{OUT}/.v.aiff", phrase], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"{OUT}/.v.aiff", "-ar", "16000", "-ac", "1",
                    "-af", "apad=pad_dur=1", "-f", "s16le", pcm], check=True)
    subprocess.run(["adb", "push", pcm, "/data/local/tmp/voice_inject.pcm"], capture_output=True)
    sh("chmod", "644", "/data/local/tmp/voice_inject.pcm")
    sh("run-as", PKG, "--user", user(), "cp", "/data/local/tmp/voice_inject.pcm", "files/voice_inject.pcm")
    ui("tap", "말로 입력")
    for _ in range(30):
        time.sleep(1)
        if ui("has", "듣고 있어요") != 0:
            break
    time.sleep(1.5)


stop = threading.Event()
threading.Thread(target=vconsole.heartbeat_forever, args=(stop,), daemon=True).start()
time.sleep(0.5)

# ---- 준비: 깨끗한 상태, 차 정차, 아이 없음 ----
sh("am", "force-stop", PKG)
sh("pm", "clear", "--user", "current", PKG)
for p in ("android.car.permission.CAR_SPEED", "android.permission.RECORD_AUDIO"):
    sh("pm", "grant", "--user", "current", PKG, p)
vconsole.gear("Park"); vconsole.speed(0)
vconsole.seat("driver", True); vconsole.seat("rear_left", False); vconsole.seat("rear_right", False)
for d in ("rl", "rr"):
    vconsole.door(d, False); vconsole.window(d, 0)
vconsole.lock(False)
vconsole.belt("LEFT", "clear"); vconsole.belt("RIGHT", "clear")
subprocess.run(["adb", "logcat", "-c"])
sh("am", "start", "--user", "current", "-n", f"{PKG}/.MainActivity")
time.sleep(5)

# ---- A: 첫 실행 → 아이 등록 안내 → (데모 가족) → 홈 → 말로 일정 → 칩 확정 → 말로 메모 ----
a = Rec("a_input")
time.sleep(1.5); a.mark("first-meet")
tap_until("시작하기", "아이 등록하기"); time.sleep(2.5); a.mark("first-register")
tap_until("아이 등록하기", "+ 아이 추가"); time.sleep(2.5); a.mark("family")
ui("tap", "=가족"); time.sleep(1.5)
# 밤 시간대 녹화라도 피코를 원래 밝기로 (설정 → 밤에는 은은하게: 끄기)
ui("tap", "=설정"); time.sleep(1.2); ui("tap", "$끄기"); time.sleep(0.6); ui("tap", "=설정"); time.sleep(1.2)
# 데모 가족·일정·메모 (데모 패널, 시연용)
ui("tap", "피코 ·", "5"); time.sleep(1.2); ui("tap", "데모 가족"); time.sleep(1.2); ui("tap", "데모 패널"); time.sleep(2)
a.mark("home-briefing"); time.sleep(4)
ui("tap", "=일정"); time.sleep(1.5); tap_until("+ 새 일정", "무엇인지 골라 주세요"); time.sleep(1)
a.mark("voice-schedule")
voice("수요일 오후 세시 태권도")
a.mark("voice-schedule-heard"); time.sleep(3)
if ui("has", "무엇인지 골라 주세요") == 0:     # 제목을 못 알아들었으면 칩으로 확정하는 장면
    ui("tap", "$태권도"); time.sleep(1.5)
subprocess.run(["adb", "shell", "input", "swipe", "2100", "1150", "2100", "500", "400"], capture_output=True)
time.sleep(0.8); ui("tap", "$도복"); time.sleep(0.6); ui("tap", "$띠"); time.sleep(1.5)
a.mark("chips"); time.sleep(2)
ui("tap", "=저장"); time.sleep(2); a.mark("schedule-saved"); time.sleep(2)
ui("tap", "=일정"); time.sleep(1.5)
ui("tap", "=메모"); time.sleep(1.5); tap_until("+ 새 메모", "무엇을 알려 드릴지"); time.sleep(0.8)
a.mark("voice-memo")
voice("콩이가 내릴 때 문 닫혔는지 확인")
a.mark("voice-memo-heard"); time.sleep(2)
if ui("has", "\"문 닫혔는지 확인\"") != 0:      # 본문을 잘못 알아들었으면 칩으로 고치는 장면
    ui("tap", "$문 닫혔는지 확인"); time.sleep(1.5)
ui("tap", "=저장"); time.sleep(2); a.mark("memo-saved"); time.sleep(1.5)
ui("tap", "=메모"); time.sleep(2.5); a.mark("home-after"); time.sleep(3)
a.stop()
sh("run-as", PKG, "--user", user(), "rm", "-f", "files/voice_inject.pcm")

# ---- B: 뒷좌석 루틴 (탑승 → 벨트 → 출발 경고 → 주행 → 도착 준비물 → 차도 쪽 문 → 인도 쪽 하차 → 남김) ----
subprocess.run(["adb", "reverse", "tcp:8099", "tcp:8099"], capture_output=True)
guardian = subprocess.Popen(["python3", "tools/guardian_server.py", "--log", f"{OUT}/guardian.jsonl"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
sh("am", "broadcast", "-a", "com.pikodrive.DEMO_GUARDIAN", "-p", PKG, "--es", "on", "true")
time.sleep(1)
b = Rec("b_ride")
vconsole.MARK_HOOK = b.mark
vconsole.run("kidride")
time.sleep(2); b.mark("end")
b.stop()
guardian.terminate()

# ---- C: 뒷좌석 화면 (보조 디스플레이, 짧게) ----
subprocess.run(["adb", "emu", "multidisplay", "add", "1", "1280", "800", "160", "0"], capture_output=True)
time.sleep(4)
sh("am", "force-stop", PKG)
sh("am", "start", "--user", "current", "-n", f"{PKG}/.MainActivity")
time.sleep(9)
rear = os.path.abspath(f"{OUT}/c_rear.webm")
subprocess.run(["adb", "emu", "screenrecord", "start", "--display", "1", "--time-limit", "30", rear], capture_output=True)
t0 = time.time()
time.sleep(3)
vconsole.door("rr", True); vconsole.seat("rear_left", True); marks["c_rear"] = {"board": round(time.time() - t0, 2)}
time.sleep(5); vconsole.door("rr", False)
vconsole.belt("LEFT", "on"); marks["c_rear"]["belt"] = round(time.time() - t0, 2)
time.sleep(8)
vconsole.gear("Drive"); vconsole.ramp(0, 30, 3); marks["c_rear"]["drive"] = round(time.time() - t0, 2)
time.sleep(6)
subprocess.run(["adb", "emu", "screenrecord", "stop"], capture_output=True)
vconsole.ramp(30, 0, 3); vconsole.gear("Park")
vconsole.seat("rear_left", False); vconsole.belt("LEFT", "clear")
subprocess.run(["adb", "emu", "multidisplay", "del", "1"], capture_output=True)

stop.set()
json.dump(marks, open(f"{OUT}/marks.json", "w"), ensure_ascii=False, indent=1)
ev = subprocess.run(["adb", "logcat", "-d", "-v", "time", "-s", "PIKO_EVIDENCE:I"], capture_output=True, text=True).stdout
open(f"{OUT}/evidence.log", "w").write(ev)
for f in (".v.pcm", ".v.aiff"):
    try:
        os.remove(f"{OUT}/{f}")
    except FileNotFoundError:
        pass
print("done →", OUT)
