#!/usr/bin/env python3
"""
v3 아이 라이드 E2E (Pleos 에뮬레이터 + 디버그 APK).
  adb forward tcp:18088 tcp:8088
  python3 tools/v3_kidride.py qa/v3/kidride
초기화 → 첫 만남 통과 → 데모 패널에서 데모 가족 불러오기 → vconsole 'kidride' 시나리오 재생,
표식(MARK)마다 화면 캡처. 증거: evidence.log(PIKO_EVIDENCE), host.log, *.png
"""
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
import vconsole  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "qa/v3/kidride"
os.makedirs(OUT, exist_ok=True)


def ui(*a):
    return subprocess.run(["python3", "tools/ui.py", *a], capture_output=True).returncode


def sh(*a):
    subprocess.run(["adb", "shell", *a], capture_output=True)


stop = threading.Event()
threading.Thread(target=vconsole.heartbeat_forever, args=(stop,), daemon=True).start()
time.sleep(0.5)

sh("am", "force-stop", "com.pikodrive")
sh("pm", "clear", "--user", "current", "com.pikodrive")
sh("pm", "grant", "--user", "current", "com.pikodrive", "android.car.permission.CAR_SPEED")
vconsole.gear("Park")
vconsole.seat("driver", True)
vconsole.seat("rear_left", False)
vconsole.seat("rear_right", False)
subprocess.run(["adb", "logcat", "-c"])
sh("am", "start", "--user", "current", "-n", "com.pikodrive/.MainActivity")
time.sleep(6)
ui("shot", f"{OUT}/00a-first-1.png")
for _ in range(8):   # 앱이 정차(P)를 확인하기 전 탭은 무시된다
    ui("tap", "시작하기"); time.sleep(1.2)
    if ui("has", "아이 등록하기") == 0:
        break
ui("shot", f"{OUT}/00a-first-2.png")
ui("tap", "아이 등록하기"); time.sleep(1.5)     # 첫 실행 → 가족 설정으로 바로
ui("shot", f"{OUT}/00a-first-3-family.png")
ui("tap", "=가족"); time.sleep(1.5)            # 뒤로 → 홈 (아이 미등록 안내)
ui("shot", f"{OUT}/00a-first-4-home-empty.png")
ui("tap", "피코 ·", "5"); time.sleep(1.2)
ui("tap", "데모 가족"); time.sleep(1)
ui("shot", f"{OUT}/00-demo-panel.png")
ui("tap", "데모 패널"); time.sleep(1)
# 보호자 알림 켜기 (설정 → 가족 → 켜기). 호스트에 tools/guardian_server.py + adb reverse
subprocess.run(["adb", "reverse", "tcp:8099", "tcp:8099"], capture_output=True)
guardian = subprocess.Popen(["python3", "tools/guardian_server.py", "--log", f"{OUT}/guardian.jsonl"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1)
# 가족 화면의 '다른 보호자에게 알림' 토글과 같은 설정값 (디버그 QA 훅)
sh("am", "broadcast", "-a", "com.pikodrive.DEMO_GUARDIAN", "-p", "com.pikodrive", "--es", "on", "true")
ui("tap", "설정"); time.sleep(1); ui("tap", "가족 설정"); time.sleep(1.2)
ui("shot", f"{OUT}/00b-family.png")
# 뒤로 두 번 (가족 → 설정 → 홈). 크로스페이드 중 탭이 사라지는 화면에 걸릴 수 있어 확인하며 반복
for header, leaving in (("=가족", "차도 쪽 문"), ("=설정", "가족 설정")):
    for _ in range(4):
        if ui("has", leaving) != 0:
            break
        ui("tap", header); time.sleep(1.5)

n = [0]


def rear_shot(name):
    """뒷좌석 화면(에뮬레이터 보조 디스플레이 1번) 캡처 — screencap이 가상 디스플레이를 못 찍어 에뮬레이터 콘솔 사용"""
    import glob
    import shutil
    tmp = os.path.abspath(f"{OUT}/.rear")
    os.makedirs(tmp, exist_ok=True)
    subprocess.run(["adb", "emu", "screenrecord", "screenshot", "--display", "1", tmp], capture_output=True)
    files = sorted(glob.glob(f"{tmp}/*.png"), key=os.path.getmtime)
    if files:
        shutil.move(files[-1], f"{OUT}/{name}")
    shutil.rmtree(tmp, ignore_errors=True)


def hook(mark):
    n[0] += 1
    if mark == "checklist":
        # 쉿 모드: 아이 대사 끄기 + 실내 조명 낮춤 → 화면·조명 확인 후 다시 끄기
        ui("tap", "=쉿 모드"); time.sleep(2.5)
        ui("shot", f"{OUT}/{n[0]:02d}a-quiet.png")
        print("ambient", __import__("json").loads(vconsole._req("GET", "/vehicle/state"))["body"]["ambient_light"], flush=True)
        ui("tap", "쉿 모드 끄기"); time.sleep(2)
    time.sleep(0.6)
    ui("shot", f"{OUT}/{n[0]:02d}-{mark}.png")
    rear_shot(f"{n[0]:02d}-{mark}-rear.png")


vconsole.MARK_HOOK = hook
with open(f"{OUT}/host.log", "w") as f:
    real_print = print

    def tee(*a, **k):
        real_print(*a, **k)
        real_print(*a, file=f, flush=True)

    vconsole.print = tee
    vconsole.run("kidride")
time.sleep(3)
ui("shot", f"{OUT}/99-end.png")
guardian.terminate()
stop.set()
ev = subprocess.run(["adb", "logcat", "-d", "-v", "time", "-s", "PIKO_EVIDENCE:I"], capture_output=True, text=True).stdout
open(f"{OUT}/evidence.log", "w").write(ev)
crash = subprocess.run(["adb", "logcat", "-d"], capture_output=True, text=True).stdout
if "FATAL EXCEPTION" in crash:
    open(f"{OUT}/crash.log", "w").write(crash[crash.index("FATAL EXCEPTION") - 200:][:6000])
    print("CRASH — see crash.log")
print("done →", OUT)
