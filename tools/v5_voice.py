#!/usr/bin/env python3
"""
v5 입력 화면 E2E: 칩 입력 + 말로 입력(Pleos STT에 합성 음성 주입).
  python3 tools/v5_voice.py qa/v5/input
에뮬레이터엔 마이크가 없다 → macOS `say -v Yuna`로 만든 16kHz PCM을 디버그 앱 files/voice_inject.pcm에 넣고 말로 입력을 누른다.
"""
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
import vconsole  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "qa/v5/input"
os.makedirs(OUT, exist_ok=True)
PKG = "com.pikodrive"


def ui(*a):
    return subprocess.run(["python3", "tools/ui.py", *a], capture_output=True).returncode


def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout


def tap_until(text, expect, tries=8):
    """앱이 정차(P)를 확인하기 전엔 탭이 무시된다 → 다음 화면 글자가 보일 때까지 다시 누른다"""
    for _ in range(tries):
        ui("tap", text)
        time.sleep(1.2)
        if ui("has", expect) == 0:
            return True
    return False


def user():
    return sh("am", "get-current-user").strip()


def voice_file(phrase):
    pcm = os.path.join(OUT, ".voice.pcm")
    aiff = os.path.join(OUT, ".voice.aiff")
    subprocess.run(["say", "-v", "Yuna", "-r", "150", "-o", aiff, phrase], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", aiff, "-ar", "16000", "-ac", "1", "-af", "apad=pad_dur=1",
                    "-f", "s16le", pcm], check=True)
    subprocess.run(["adb", "push", pcm, "/data/local/tmp/voice_inject.pcm"], capture_output=True)
    sh("chmod", "644", "/data/local/tmp/voice_inject.pcm")
    sh("run-as", PKG, "--user", user(), "cp", "/data/local/tmp/voice_inject.pcm", "files/voice_inject.pcm")


def say(phrase, shot):
    voice_file(phrase)
    ui("tap", "말로 입력")
    time.sleep(1.5)
    ui("shot", f"{OUT}/{shot}-listening.png")
    # 듣기가 끝날 때까지 (최종 결과 또는 12초 시간 초과 + 재시도)
    for _ in range(30):
        time.sleep(1)
        if ui("has", "듣고 있어요") != 0:
            break
    time.sleep(1)
    ui("shot", f"{OUT}/{shot}-heard.png")


stop = threading.Event()
threading.Thread(target=vconsole.heartbeat_forever, args=(stop,), daemon=True).start()
time.sleep(0.5)
sh("am", "force-stop", PKG)
sh("pm", "clear", "--user", "current", PKG)
sh("pm", "grant", "--user", "current", PKG, "android.car.permission.CAR_SPEED")
sh("pm", "grant", "--user", "current", PKG, "android.permission.RECORD_AUDIO")
vconsole.gear("Park")
subprocess.run(["adb", "logcat", "-c"])
sh("am", "start", "--user", "current", "-n", f"{PKG}/.MainActivity")
time.sleep(6)
tap_until("시작하기", "나중에")
tap_until("나중에", "피코 ·")
ui("tap", "피코 ·", "5"); time.sleep(1.2)
ui("tap", "데모 가족"); time.sleep(1)
ui("tap", "데모 패널"); time.sleep(1.5)

# 1) 일정: 목록 → 말로 새 일정 → 저장
ui("tap", "=일정"); time.sleep(1.5)
ui("shot", f"{OUT}/01-schedule-list.png")
say("수요일 오후 세시 태권도", "02-schedule-voice")
ui("tap", "=저장"); time.sleep(1.2)
ui("shot", f"{OUT}/03-schedule-saved.png")

# 2) 일정: 칩만으로 새 일정 (키보드 없이). 음성으로 만든 초안이 남아 있으면(제목 못 알아들음) 먼저 닫는다
if ui("has", "=취소") == 0:
    ui("tap", "=취소"); time.sleep(1)
tap_until("+ 새 일정", "무엇인지 골라 주세요")
ui("tap", "콩이 ·"); ui("tap", "$수영"); ui("tap", "=화"); ui("tap", "=16시"); ui("tap", "=:30")
# 준비물 줄은 화면 아래 → 편집 영역을 올려서 칩이 보이게 한 뒤 누른다 (안 보이면 왼쪽 목록의 같은 글자를 누르게 됨)
subprocess.run(["adb", "shell", "input", "swipe", "2100", "1150", "2100", "500", "400"], capture_output=True)
time.sleep(0.8)
ui("tap", "$수영 가방")
time.sleep(0.8)
ui("shot", f"{OUT}/04-schedule-chips.png")
ui("tap", "=저장"); time.sleep(1.2)
ui("shot", f"{OUT}/05-schedule-saved2.png")
ui("tap", "=일정"); time.sleep(1.5)   # 뒤로

# 3) 메모: 말로
ui("tap", "=메모"); time.sleep(1.5)
ui("tap", "+ 새 메모"); time.sleep(1)
say("콩이가 내릴 때 문 닫혔는지 확인", "06-memo-voice")
ui("shot", f"{OUT}/07-memo-voice-result.png")
ui("tap", "=저장"); time.sleep(1.2)
ui("shot", f"{OUT}/08-memo-saved.png")
ui("tap", "=메모"); time.sleep(1.5)
ui("shot", f"{OUT}/09-home.png")

sh("run-as", PKG, "--user", user(), "rm", "-f", "files/voice_inject.pcm")
stop.set()
ev = subprocess.run(["adb", "logcat", "-d", "-v", "time", "-s", "PIKO_EVIDENCE:I"], capture_output=True, text=True).stdout
open(f"{OUT}/evidence.log", "w").write(ev)
crash = subprocess.run(["adb", "logcat", "-d", "-b", "crash"], capture_output=True, text=True).stdout
if PKG + "," in crash or f"Process: {PKG}" in crash:
    open(f"{OUT}/crash.log", "w").write(crash[-6000:])
    print("CRASH — see crash.log")
for f in (".voice.pcm", ".voice.aiff"):
    try:
        os.remove(os.path.join(OUT, f))
    except FileNotFoundError:
        pass
print("done →", OUT)
