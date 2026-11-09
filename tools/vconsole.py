#!/usr/bin/env python3
"""
Pleos Connect Virtual Console 드라이버.

에뮬레이터 안 웹 콘솔(8088)의 HTTP API로 기어·속도를 주입한다.
user 빌드라 adb VHAL 주입이 막혀 있어서 이 경로를 쓴다 (qa/S0/emulator-setup.md).

  adb forward tcp:18088 tcp:8088
  python3 tools/vconsole.py gear Drive
  python3 tools/vconsole.py speed 60
  python3 tools/vconsole.py scenario probe      # S0 프로브용 시나리오
  python3 tools/vconsole.py state
"""
import json
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:18088"
CLIENT = "piko-vconsole"


def _req(method, path, retries=2):
    """HTTP 2xx가 아니면 재시도 후 예외. 시나리오가 실패한 채 진행되지 않게 한다."""
    last = None
    for _ in range(retries + 1):
        try:
            r = urllib.request.Request(BASE + path, method=method)
            with urllib.request.urlopen(r, timeout=5) as res:
                return res.read().decode()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.2)
    raise RuntimeError(f"{method} {path} failed: {last}")


def heartbeat_forever(stop):
    # 콘솔은 브라우저 세션 하트비트가 끊기면 입력을 비활성화한다 (timeout 3s)
    misses = 0
    while not stop.is_set():
        try:
            _req("POST", f"/browser/session?client={CLIENT}", retries=0)
            misses = 0
        except Exception:
            misses += 1
            if misses >= 2:
                log("WARN heartbeat lost (>2s) — console input may be inactive")
        stop.wait(1.0)


def gear(g):
    _req("PUT", f"/vehicle/gear?gear={g}")
    actual = json.loads(_req("GET", "/vehicle/state"))["gear"]["actual"]
    if not actual or actual.get("value") != g:
        raise RuntimeError(f"gear {g} not applied (actual={actual})")
    log(f"gear {g}")


def speed(kph, brake=0, accel=0):
    # 콘솔은 정수 km/h만 받는다 (소수 → 400 invalid_speed)
    _req("PUT", f"/vehicle/controls?speed={int(kph + 0.5)}&accelerator={accel}&brake={brake}")


def battery(pct):
    _req("PUT", f"/vehicle/ev-battery?field=soc&value={int(pct)}")
    log(f"battery {pct}%")


def charge_port(open_):
    _req("PUT", f"/vehicle/ev-battery?field=charge_port_open&value={'true' if open_ else 'false'}")
    log(f"charge port {'open' if open_ else 'closed'}")


def passenger(present):
    _req("PUT", f"/vehicle/seat?kind=occupied&area=passenger&value={'true' if present else 'false'}")
    log(f"passenger {'in' if present else 'out'}")


def seat(area, present):
    """area: driver | passenger | rear_left | rear_right"""
    _req("PUT", f"/vehicle/seat?kind=occupied&area={area}&value={'true' if present else 'false'}")
    log(f"seat {area} {'in' if present else 'out'}")


def door(name, open_):
    """name: rl | rr | fl | fr"""
    _req("PUT", f"/vehicle/body?door_{name}={1 if open_ else 0}")
    log(f"door {name} {'open' if open_ else 'closed'}")


def lock(on):
    _req("PUT", f"/vehicle/body?doors_locked={1 if on else 0}")
    log(f"doors {'locked' if on else 'unlocked'}")


def window(name, pct):
    _req("PUT", f"/vehicle/body?window_{name}={int(pct)}")
    log(f"window {name} {pct}%")


def belt(seat_name, value):
    """벨트는 콘솔 주입 경로가 없다 → 디버그 빌드의 데모 대체 입력 브로드캐스트 (화면에 '시뮬레이션 입력' 배지)"""
    import subprocess
    v = {"on": "true", "off": "false"}.get(value, "clear")
    subprocess.run(["adb", "shell", "am", "broadcast", "-a", "com.pikodrive.DEMO_BELT", "-p", "com.pikodrive",
                    "--es", "seat", seat_name, "--es", "value", v], capture_output=True)
    log(f"belt {seat_name} {value} (simulated input)")


def state():
    d = json.loads(_req("GET", "/vehicle/state"))  # 응답이 크다: 필요한 값만
    return d["gear"]["actual"], d["controls"]["speed_kph"]


T0 = time.time()
# 시나리오 표식 지점에서 호출 (스크린샷 등). tools/v3_kidride.py 가 설정한다
MARK_HOOK = None


def log(msg):
    # 절대 시각(초.밀리초)을 같이 찍어 기기 logcat 과 대조할 수 있게 한다
    now = time.time()
    print(f"[host {time.strftime('%M:%S', time.localtime(now))}.{int(now * 1000) % 1000:03d} +{(now - T0) * 1000:6.0f}ms] {msg}", flush=True)


def ramp(start, end, seconds, hz=10, brake=0):
    steps = max(1, int(seconds * hz))
    for i in range(1, steps + 1):
        speed(start + (end - start) * i / steps, brake=brake)
        time.sleep(1 / hz)
    log(f"speed {start}→{end} in {seconds}s")


SCENARIOS = {
    # P 대기 → D 출발 → 60km/h → 급제동(60→10, 2.5초 = -20km/h/s) → 정지 → R → P
    # D 주행 중 발화 → 3초 후 R (S3 '발화 중 후진 1초 내 중단' 측정)
    "reverse-stop": [
        ("gear", "Drive"), ("ramp", 0, 0, 1), ("hold", 3),
        ("gear", "Reverse"), ("hold", 2), ("gear", "Park"),
    ],
    # 감지 임계값 검증 (S3): 감속 −8 / −12 / −20 km/h/s × 속도대 60·100, 급가속 +14, 정속 노이즈 없음
    # 각 구간 사이 12초 정속 → 판정 창이 서로 섞이지 않게
    "thresholds": [
        ("gear", "Park"), ("hold", 2), ("gear", "Drive"),
        ("ramp", 0, 60, 6), ("hold", 12),
        ("ramp", 60, 36, 3), ("hold", 12),          # A: −8 km/h/s  → 미검출이어야 함
        ("ramp", 36, 60, 4), ("hold", 12),
        ("ramp", 60, 24, 3), ("hold", 12),          # B: −12 km/h/s → 검출
        ("ramp", 24, 100, 8), ("hold", 12),
        ("ramp", 100, 40, 3), ("hold", 12),         # C: −20 km/h/s @100 → 검출
        ("ramp", 40, 100, 5), ("hold", 12),
        ("ramp", 100, 76, 3), ("hold", 12),         # D: −8 km/h/s @100 → 미검출
        ("ramp", 76, 30, 2), ("hold", 12),          # E: −23 km/h/s 짧게 2초 → 검출
        ("ramp", 30, 72, 3), ("hold", 12),          # F: +14 km/h/s 급가속 → 검출
        ("ramp", 72, 0, 8), ("hold", 2), ("gear", "Park"),
    ],
    # 실제 앱: 급제동 → 2.5초 뒤 놀람 발화 시작 → 그 직후 R (S3 필수 증거: 발화 중 후진 1초 내 무음)
    "reverse-during-speech": [
        ("gear", "Park"), ("hold", 2), ("gear", "Drive"),
        ("ramp", 0, 60, 6), ("hold", 6),
        ("ramp", 60, 10, 2.5), ("ramp", 10, 0, 0.5),
        ("hold", 0.9), ("gear", "Reverse"), ("hold", 3), ("gear", "Park"), ("hold", 3),
    ],
    # PT 90초 데모 (core Scenarios.commuteCompressed 와 같은 각본, 실차 데이터 경로로 재생)
    "demo90": [
        ("gear", "Park"), ("hold", 5),
        ("gear", "Drive"), ("hold", 1),
        ("ramp", 0, 40, 8), ("hold", 10),
        ("ramp", 40, 45, 6), ("ramp", 45, 40, 6), ("hold", 14),
        ("ramp", 40, 50, 6), ("hold", 10),
        ("ramp", 50, 25, 2), ("hold", 1),          # 급제동 −12.5 km/h/s
        ("ramp", 25, 0, 5), ("hold", 1),
        ("gear", "Reverse"), ("ramp", 0, 5, 2), ("hold", 2), ("ramp", 5, 0, 2),
        ("gear", "Park"), ("hold", 9),
    ],
    # 가족·EV 시나리오 (core Scenarios.familyEv 와 같은 흐름): 동승자 탑승 → 출발 → 배터리 부족 → 정차 → 충전
    "family": [
        ("gear", "Park"), ("battery", 32), ("port", False), ("passenger", False), ("hold", 3),
        ("passenger", True), ("hold", 4),
        ("gear", "Drive"), ("ramp", 0, 40, 6), ("hold", 25),
        ("battery", 18), ("hold", 10),
        ("ramp", 40, 0, 6), ("gear", "Park"), ("hold", 6),
        ("port", True), ("hold", 6),
        ("port", False), ("battery", 80), ("passenger", False),
    ],
    # 영상 사전 준비: 데모 가속 상태에서 짧게 달려 '얼굴' 단계까지 키운다 (표정이 보이게)
    "pregrow": [
        ("gear", "Park"), ("battery", 64), ("passenger", False), ("port", False), ("hold", 2),
        ("gear", "Drive"), ("ramp", 0, 30, 3), ("hold", 14), ("ramp", 30, 0, 3), ("gear", "Park"), ("hold", 5),
    ],
    # 시연 영상 본편 (~85초): 동승자 → 출발 → 칭찬 → 급제동 → 배터리 부족 → 후진 → 도착·성장 → 충전
    "showcase": [
        ("gear", "Park"), ("hold", 4),
        ("passenger", True), ("hold", 5),
        ("gear", "Drive"), ("ramp", 0, 40, 6), ("hold", 34),
        ("ramp", 40, 50, 4), ("hold", 3),
        ("ramp", 50, 22, 2), ("hold", 6),          # 급제동 −14 km/h/s
        ("ramp", 22, 35, 3), ("hold", 28),         # 놀람 대사 후 30초 발화 간격을 지켜 배터리 알림이 나오게
        ("battery", 18), ("hold", 8),
        ("ramp", 35, 22, 3),
        ("ramp", 22, 0, 4), ("hold", 1),
        ("gear", "Reverse"), ("ramp", 0, 5, 2), ("hold", 1), ("ramp", 5, 0, 2),
        ("gear", "Park"), ("hold", 9),
        ("port", True), ("hold", 7),
    ],
    # v3 아이 라이드 (docs/CONCEPT_v3_kids_ride.md 5장): 탑승·벨트·출발 경고·주행 중 풀림·도착 준비물·
    # 차도 쪽 문·인도 쪽 하차 스티커·남김 보조 알림. 앱에 데모 가족(왼쪽 지우·초등, 오른쪽 콩이·유치원)이 있어야 한다
    "kidride": [
        ("gear", "Park"), ("seat", "driver", True), ("seat", "rear_left", False), ("seat", "rear_right", False),
        ("door", "rl", False), ("door", "rr", False), ("lock", False), ("window", "rr", 0), ("window", "rl", 0),
        ("belt", "LEFT", "clear"), ("belt", "RIGHT", "clear"), ("hold", 4),
        # 지우 탑승(인도 쪽 문) → 스스로 벨트 → 스티커
        ("door", "rr", True), ("hold", 1), ("seat", "rear_left", True), ("hold", 1), ("door", "rr", False),
        ("hold", 2), ("belt", "LEFT", "on"), ("hold", 3), ("mark", "self-belt"),
        # 콩이 탑승, 벨트 미체결 8초 → 벨트 놀이, 뒷창문 50%
        ("door", "rr", True), ("hold", 1), ("seat", "rear_right", True), ("belt", "RIGHT", "off"),
        ("hold", 1), ("door", "rr", False), ("window", "rr", 50), ("hold", 11), ("mark", "checklist"),
        # 미체결로 D → 운전자 음성, 이후 체결·창문 닫힘·잠금
        ("gear", "Drive"), ("hold", 1), ("mark", "depart-unbuckled"), ("hold", 4),
        ("belt", "RIGHT", "on"), ("window", "rr", 0), ("lock", True),
        ("ramp", 0, 40, 6), ("hold", 8),
        # 주행 중 지우 벨트 풀림 → 운전자 음성
        ("belt", "LEFT", "off"), ("hold", 2), ("mark", "belt-release"), ("hold", 4), ("belt", "LEFT", "on"), ("hold", 6),
        # 도착: 오늘 준비물
        ("ramp", 40, 0, 6), ("gear", "Park"), ("lock", False), ("hold", 6), ("mark", "arrived"),
        # 차도 쪽(왼쪽) 문 열림 경고
        ("belt", "LEFT", "off"), ("door", "rl", True), ("hold", 3), ("mark", "street-door"), ("hold", 2), ("door", "rl", False), ("hold", 2),
        # 콩이 인도 쪽 하차 → 스티커
        ("belt", "RIGHT", "off"), ("door", "rr", True), ("hold", 2), ("seat", "rear_right", False), ("hold", 3),
        ("door", "rr", False), ("mark", "curb-exit"), ("hold", 2),
        # 운전자 하차, 지우는 남음 → 남김 보조 알림 (10초마다 반복)
        ("seat", "driver", False), ("hold", 3), ("mark", "left-behind"), ("hold", 8),
        ("seat", "rear_left", False), ("hold", 2), ("seat", "driver", True), ("hold", 3), ("mark", "clear"),
        ("belt", "LEFT", "clear"), ("belt", "RIGHT", "clear"),
    ],
    "probe": [
        ("gear", "Park"), ("hold", 3),
        ("gear", "Drive"), ("ramp", 0, 60, 8), ("hold", 4),
        ("ramp", 60, 10, 2.5, 80), ("ramp", 10, 0, 2), ("hold", 2),
        ("gear", "Reverse"), ("ramp", 0, 5, 2), ("ramp", 5, 0, 1),
        ("gear", "Park"), ("hold", 4),
    ],
}


def run(name):
    for step in SCENARIOS[name]:
        kind = step[0]
        if kind == "gear":
            gear(step[1])
        elif kind == "battery":
            battery(step[1])
        elif kind == "port":
            charge_port(step[1])
        elif kind == "passenger":
            passenger(step[1])
        elif kind == "ramp":
            ramp(*step[1:])
        elif kind == "hold":
            time.sleep(step[1])
        elif kind == "seat":
            seat(step[1], step[2])
        elif kind == "door":
            door(step[1], step[2])
        elif kind == "lock":
            lock(step[1])
        elif kind == "window":
            window(step[1], step[2])
        elif kind == "belt":
            belt(step[1], step[2])
        elif kind == "mark":
            log(f"MARK {step[1]}")
            if MARK_HOOK:
                MARK_HOOK(step[1])


def main():
    stop = threading.Event()
    threading.Thread(target=heartbeat_forever, args=(stop,), daemon=True).start()
    time.sleep(0.3)
    cmd = sys.argv[1] if len(sys.argv) > 1 else "state"
    try:
        if cmd == "gear":
            gear(sys.argv[2])
        elif cmd == "speed":
            speed(float(sys.argv[2]))
        elif cmd == "battery":
            battery(int(sys.argv[2]))
        elif cmd == "port":
            charge_port(sys.argv[2] == "open")
        elif cmd == "passenger":
            passenger(sys.argv[2] == "in")
        elif cmd == "scenario":
            run(sys.argv[2])
        g, s = state()
        log(f"state gear={g and g.get('value')} speed={s}")
    except Exception as e:  # noqa: BLE001
        log(f"FAIL {e}")
        sys.exit(1)
    finally:
        stop.set()


if __name__ == "__main__":
    main()
