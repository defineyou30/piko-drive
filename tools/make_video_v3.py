#!/usr/bin/env python3
"""
시연 영상 v3 합성 (v4 컨셉: 아이 일정·안전 비서). 원본은 tools/record_v4.py.
  python3 tools/make_video_v3.py qa/video4 submission/video/피코드라이브_시연영상.mp4
레이아웃: 왼쪽 앱 확대(운전석 화면) + 오른쪽 자막 패널(장면 제목, 피코가 실제로 한 말, Pleos 원 화면 PIP).
자막의 대사는 evidence.log의 lineId 문장 그대로. 앱 음성은 화면 녹화에 담기지 않아 자막으로 넣는다.
장면 시각은 marks.json(녹화 기준 초).
"""
import json
import os
import subprocess
import sys
import tempfile

SRC = sys.argv[1] if len(sys.argv) > 1 else "qa/video4"
OUT = sys.argv[2] if len(sys.argv) > 2 else "submission/video/피코드라이브_시연영상.mp4"
GUARDIAN = sys.argv[3] if len(sys.argv) > 3 else os.path.join(SRC, "guardian.png")
FONT = "app/src/main/res/font/pretendard_semibold.otf"
FONTB = "app/src/main/res/font/pretendard_bold.otf"
BG, AMBER, WHITE, GRAY, ALERT = "0x0c0c0c", "0xFFB547", "0xF4EFE6", "0xB4ACA2", "0xFF7A59"
TMP = tempfile.mkdtemp()
M = json.load(open(os.path.join(SRC, "marks.json")))


def tf(name, text):
    p = os.path.join(TMP, name)
    open(p, "w", encoding="utf-8").write(text)
    return p


def run(args):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)


def dt(**kw):
    """drawtext (textfile + expansion=none: %·: 같은 글자를 그대로)"""
    parts = [f"{k}={v}" for k, v in kw.items()]
    return "drawtext=" + ":".join(parts)


n = [0]


def seg(src, start, end, title, body, speed=1, warn=False, rear=False):
    """원본 [start, end)초를 잘라 레이아웃에 얹는다"""
    n[0] += 1
    out = os.path.join(TMP, f"s{n[0]:02d}.mp4")
    t, b = tf(f"t{n[0]}.txt", title), tf(f"b{n[0]}.txt", body)
    label = tf(f"l{n[0]}.txt", "실측 녹화 · Pleos Connect 에뮬레이터 v2.1.0\n차량 신호: Virtual Console" + ("\n뒷좌석 화면: 보조 디스플레이" if rear else ""))
    pts = f"setpts=PTS/{speed}" if speed != 1 else "setpts=PTS-STARTPTS"
    if rear:
        # 뒷좌석 화면(1280x800) → 왼쪽 1501x938 가운데
        vf = (f"[0:v]{pts},scale=1501:938,pad=1920:1080:0:71:color={BG}[l1];")
    else:
        vf = (f"[0:v]{pts},split=2[a][b];"
              f"[a]crop=1668:1188:862:100,scale=1501:1069,pad=1920:1080:0:6:color={BG}[l0];"
              f"[b]scale=380:214[pip];[l0][pip]overlay=1520:24:shortest=1[l1];")
    color = ALERT if warn else AMBER
    vf += (f"[l1]{dt(fontfile=FONTB, textfile=repr(t), expansion='none', fontcolor=color, fontsize=40, x=1528, y=300)},"
           f"{dt(fontfile=FONT, textfile=repr(b), expansion='none', fontcolor=WHITE, fontsize=27, line_spacing=14, x=1528, y=372)},"
           f"{dt(fontfile=FONT, textfile=repr(label), expansion='none', fontcolor=GRAY, fontsize=19, line_spacing=8, x=1528, y=960)},"
           f"{'' if speed == 1 else dt(fontfile=FONTB, text=repr(f'{speed}배속'), fontcolor=GRAY, fontsize=24, x=1528, y=250) + ','}"
           f"fps=30[v]")
    run(["-ss", f"{start:.2f}", "-t", f"{end - start:.2f}", "-i", src,
         "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
         "-filter_complex", vf, "-map", "[v]", "-map", "1:a", "-t", f"{(end - start) / speed:.3f}",
         "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", out])
    return out


def card(seconds, lines, img=None):
    """[(text, font, size, color, x, y)] 카드 (+ 이미지)"""
    n[0] += 1
    out = os.path.join(TMP, f"c{n[0]:02d}.mp4")
    inputs = ["-f", "lavfi", "-i", f"color=c={BG}:s=1920x1080:d={seconds}:r=30"]
    filt = []
    for i, (text, font, size, color, x, y) in enumerate(lines):
        p = tf(f"c{n[0]}_{i}.txt", text)
        filt.append(dt(fontfile=font, textfile=repr(p), expansion="none", fontcolor=color, fontsize=size, line_spacing=18, x=x, y=y))
    if img:
        inputs += ["-i", img]
        fc = f"[1]scale=-1:900[im];[0][im]overlay=x=1180:y=(H-h)/2," + ",".join(filt) + "[v]"
        amap = "2:a"
    else:
        fc = "[0]" + ",".join(filt) + "[v]"
        amap = "1:a"
    run([*inputs, "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-filter_complex", fc,
         "-map", "[v]", "-map", amap, "-t", str(seconds), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", out])
    return out


def main():
    a, b, c = (os.path.join(SRC, f) for f in ("a_input.mp4", "b_ride.mp4", "c_rear.webm"))
    A, B, C = M["a_input"], M["b_ride"], M["c_rear"]
    parts = []
    # 0) 콜드 오픈: 아이를 두고 내리면
    parts.append(seg(b, B["left-behind"] - 1, B["left-behind"] + 6, "아이를 두고 내리면",
                     "운전석이 비었는데\n뒷좌석 착석 신호가 남아 있으면\n피코가 반복해서 알려요\n\n보조 알림 · 센서를 못 읽으면\n'확인 불가'로 표시", warn=True))
    # 1) 제목 + 검증 범위를 먼저 밝힌다 (심사 리뷰: 실제 신호와 대체 입력 구분을 앞에)
    parts.append(card(6, [
        ("피코 드라이브", FONTB, 92, WHITE, 160, 300),
        ("아이 일정·안전 보조 비서", FONT, 44, GRAY, 166, 420),
        ("부모가 체크하지 않고, 차가 먼저 살핍니다.", FONTB, 44, AMBER, 166, 500),
        ("이 영상: Pleos Connect 에뮬레이터 실측 녹화 · 차량 신호는 Virtual Console로 주입\n"
         "벨트만 콘솔 주입 경로가 없어 시연용 대체 입력(화면에 표시) · 앱 음성은 녹화되지 않아 실제 문장을 자막으로",
         FONT, 26, GRAY, 166, 700),
    ]))
    # 2) 핵심 루틴 먼저
    parts.append(seg(a, A["home-briefing"] - 1, A["home-briefing"] + 5, "정차 중 홈",
                     "맨 위 한 줄 = 지금 할 일\n다음 일정과 준비물\n\n피코가 같은 내용을\n한 번 말해 줘요"))
    parts.append(seg(b, B["self-belt"] - 9, B["self-belt"] + 1, "아이들이 타면",
                     "뒷좌석 착석 신호로\n아이별 체크가 시작돼요\n\n벨트를 매면 스티커 한 장\n(벨트는 시연용 대체 입력)"))
    parts.append(seg(c, max(0, C["board"] - 0.5), C["belt"] + 4, "뒷좌석 화면",
                     "아이에겐 피코가 친구\n\"딸깍, 지우 벨트 됐네.\n스티커 하나 붙였어.\"\n\n보조 디스플레이로 검증\n없는 차는 음성만으로 동작", rear=True))
    parts.append(seg(b, B["checklist"] - 3, B["depart-unbuckled"] + 6, "벨트 안 맨 채 D",
                     "\"오른쪽 뒷좌석 콩이 벨트가\n채워지지 않았어요.\"\n\n주행 중엔 글자·버튼 없이\n음성으로만", warn=True))
    parts.append(seg(b, B["depart-unbuckled"] + 6, B["belt-release"] - 2, "주행 중", "화면엔 피코 얼굴만\n(터치할 것 없음)", speed=4))
    parts.append(seg(b, B["belt-release"] - 2, B["belt-release"] + 5, "주행 중 벨트 풀림",
                     "\"왼쪽 뒷좌석 지우 벨트가\n풀렸어요.\"", warn=True))
    parts.append(seg(b, B["arrived"] - 1, B["arrived"] + 2.5, "도착",
                     "그 무렵 일정의 준비물을\n아이에게 알려 줘요\n\n지우 도복·띠\n콩이 수영 가방"))
    parts.append(seg(b, B["street-door"] - 2, B["street-door"] + 4, "차도 쪽 문 (설정값)",
                     "\"왼쪽 뒷좌석 문은\n차도 쪽이에요.\n반대쪽으로 내려 주세요.\"\n\n차도 쪽 방향은 부모가 설정", warn=True))
    parts.append(seg(b, B["curb-exit"] - 4, B["curb-exit"] + 1.5, "인도 쪽 하차",
                     "인도 쪽 문으로 내리면 스티커\n메모 \"콩이 쪽 문\n닫혔는지 확인\"이 이때"))
    parts.append(seg(b, B["left-behind"] - 1, B["clear"] + 1, "뒷좌석 남김 보조 알림",
                     "10초마다 반복\n착석 신호가 풀리면 멈추고\n재생 중이던 알림도 끊어요", warn=True, speed=1.5))
    # 3) 일정·메모는 이렇게 넣어요 (압축)
    parts.append(seg(a, A["first-meet"] - 0.5, A["first-register"] + 3, "처음 켜면",
                     "피코가 소개하고\n아이 등록으로 바로 안내해요\n\n아이는 별칭만 저장", speed=2))
    parts.append(seg(a, A["voice-schedule"], A["voice-schedule-heard"] + 2, "말로 입력 (초안)",
                     "\"수요일 오후 3시 태권도\"\n\n인식된 칸만 초안으로 채움\n(Pleos SpeechToText, 기기 내)\n합성 음성 6회: 요일·시각 6/6,\n제목 2/6", speed=2.5))
    parts.append(seg(a, A["voice-schedule-heard"] + 2, A["schedule-saved"] + 1, "칩으로 확정",
                     "못 알아들은 칸은 칩으로\n요약 문장을 보고 저장", speed=2.5))
    parts.append(seg(a, A["voice-memo"], A["memo-saved"] + 1, "상황 메모",
                     "\"콩이가 내릴 때\n문 닫혔는지 확인\"\n\n그 순간을 차량 신호로\n알아채서 말해 줘요", speed=2.5))
    # 4) 보호자 화면·검증 범위
    if os.path.exists(GUARDIAN):
        parts.append(card(5, [
            ("다른 보호자에게 (선택)", FONTB, 52, WHITE, 160, 330),
            ("출발·도착·하차·남김", FONT, 40, GRAY, 166, 420),
            ("별칭·좌석·시각만 · 위치 없음\n부모가 켤 때만 · HTTPS 웹훅", FONT, 34, AMBER, 166, 500),
            ("시연: 로컬 웹훅 6건 수신 화면\n모바일 푸시·계정은 출시 전 과제", FONT, 28, GRAY, 166, 660),
        ], img=GUARDIAN))
    parts.append(card(8, [
        ("검증 범위", FONTB, 56, WHITE, 120, 140),
        ("에뮬레이터에서 확인", FONTB, 34, AMBER, 120, 260),
        ("뒷좌석 착석·벨트 읽기·뒷문\n문 잠금·뒷창문·조명 신호\n루틴 9장면 E2E\n릴리스 APK 실행\n코어 테스트 135개", FONT, 30, WHITE, 120, 320),
        ("대체·제한", FONTB, 34, AMBER, 700, 260),
        ("벨트 변경: 시연용 대체 입력\n뒷좌석 화면: 보조 디스플레이\n말로 입력: 합성 음성 주입\n보호자 알림: 로컬 웹훅", FONT, 30, WHITE, 700, 320),
        ("출시 전 과제 (실차)", FONTB, 34, AMBER, 1280, 260),
        ("착석·벨트 값 방향과 지연\n남김 알림 오탐·미탐\n실제 마이크 인식률\n모바일 푸시·계정", FONT, 30, WHITE, 1280, 320),
        ("근거와 재현 방법 — GitHub 저장소 qa/EVIDENCE.md · Pleos Vehicle SDK · TextToSpeech · SpeechToText", FONT, 26, GRAY, 120, 900),
    ]))
    lst = os.path.join(TMP, "list.txt")
    open(lst, "w").writelines(f"file '{p}'\n" for p in parts)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    run(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", OUT])
    d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", OUT],
                       capture_output=True, text=True).stdout.strip()
    print(f"{OUT} {float(d):.1f}s")


if __name__ == "__main__":
    main()
