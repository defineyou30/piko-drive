#!/usr/bin/env python3
"""
시연 영상 v2 합성 (평가관 피드백 반영: 콜드 오픈, 앱 확대 + Pleos PIP, 배속, 상시 실측·가속 표기).

원본: qa/video/showcase.mp4 (tools/record_showcase.sh, 2560x1440 Pleos 에뮬레이터 녹화. 64MB라 git 제외, 저장소에는 720p 압축본 showcase_720.mp4)
  python3 tools/make_video_v2.py qa/video/showcase.mp4 submission/video/피코드라이브_시연영상.mp4

자막 시각은 qa/video/evidence.log 의 이벤트 시각(녹화 기준)에서 잡았다. 대사 문구는 실제 발화 lineId 의 문장 그대로.
"""
import os
import subprocess
import sys
import tempfile

SRC = sys.argv[1] if len(sys.argv) > 1 else "qa/video/showcase.mp4"
OUT = sys.argv[2] if len(sys.argv) > 2 else "submission/video/피코드라이브_시연영상.mp4"
FONT = "app/src/main/res/font/pretendard_semibold.otf"
FONTB = "app/src/main/res/font/pretendard_bold.otf"
PIXEL = "tools/fonts/Galmuri11-Bold.ttf"  # 영상용 외곽선 버전 (앱의 비트맵 버전은 ffmpeg가 크기 조절을 못 함)
BG, AMBER, WHITE, GRAY = "0x121212", "0xFFB547", "0xF4EFE6", "0xB4ACA2"
TMP = tempfile.mkdtemp()

# (시작, 끝, 배속, 제목, 피코 대사 또는 설명)
SEGMENTS = [
    (2.5, 9.0, 1, "동승자가 탔어요", "“옆자리 친구도 안녕!”\nPleos 좌석 신호"),
    (9.0, 17.0, 1, "기어 D · 출발", "버튼과 글자가 사라지고\n말로만 소통해요\n“오늘도 잘 부탁해.”"),
    (17.0, 44.0, 4, "부드럽게 달리는 중", "아래 픽셀 바가\n편안한 시간만큼 차올라요\n(4배속)"),
    (44.0, 52.0, 1, "편안한 주행이 이어지면", "“흔들림 없어서 포근해.”"),
    (52.0, 58.0, 3, "순항", "(3배속)"),
    (58.0, 67.0, 1, "급제동 감지", "2.5초 뒤 놀란 눈\n“앗, 깜짝 놀랐어!”\n운전자를 탓하지 않아요"),
    (67.0, 95.0, 5, "다시 부드럽게", "주행 중 터치 화면 0\n대사는 30초에 한 문장 이하\n(5배속)"),
    (95.0, 104.0, 1, "배터리 18%", "피코도 졸려요\n“배터리가 졸려. 쉬어갈까?”\nPleos 배터리 신호"),
    (104.0, 113.0, 3, "주차하러 가는 중", "(3배속)"),
    (113.0, 120.0, 1, "후진 R", "피코는 멈추고 조용히\n앞에 뜬 화면은\nPleos 후방 카메라"),
    (120.0, 128.0, 1, "정차 P · 성장", "쌓인 픽셀이 켜지며\n완성 단계로\n“오늘도 고마웠어.”"),
    (128.0, 137.0, 1, "충전구 열림", "“냠냠, 전기 맛있어!”\n\n성장 가속 ×360 시연 —\n실제로는 약 9시간 분량"),
]
COLD = (58.5, 63.8)


def text_file(name, text):
    path = os.path.join(TMP, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def run(args):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)


def layout_filter(title_file, body_file, speed):
    """좌: 앱 영역 확대(1501x1080), 우: Pleos 전체 화면 PIP + 자막 패널"""
    pts = f"setpts=PTS/{speed}" if speed != 1 else "setpts=PTS"
    label = text_file(f"label_{speed}.txt", "실측 녹화\nPleos 에뮬레이터 v2.1.0\nVirtual Console 차량 신호")
    return (
        f"[0:v]{pts},split=2[a][b];"
        f"[a]crop=1682:1210:853:90,scale=1501:1080,pad=1920:1080:0:0:color={BG}[l1];"
        f"[b]scale=419:236[pip];"
        f"[l1][pip]overlay=1501:0:shortest=1[l2];"
        f"[l2]drawtext=fontfile={FONTB}:textfile='{title_file}':expansion=none:fontcolor={AMBER}:fontsize=38:x=1524:y=290,"
        f"drawtext=fontfile={FONT}:textfile='{body_file}':expansion=none:fontcolor={WHITE}:fontsize=30:line_spacing=14:x=1524:y=360,"
        f"drawtext=fontfile={FONT}:textfile='{label}':expansion=none:fontcolor={GRAY}:fontsize=22:line_spacing=8:x=1524:y=950,"
        f"fps=30[v]"
    )


def segment(i, start, end, speed, title, body):
    out = os.path.join(TMP, f"s{i:02d}.mp4")
    tf, bf = text_file(f"t{i}.txt", title), text_file(f"b{i}.txt", body)
    run([
        "-ss", str(start), "-t", str(end - start), "-i", SRC,
        "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
        "-filter_complex", layout_filter(tf, bf, speed),
        "-map", "[v]", "-map", "1:a", "-t", f"{(end - start) / speed:.3f}",
        "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", out,
    ])
    return out


def card(i, seconds, filters, overlay_img=None):
    out = os.path.join(TMP, f"c{i:02d}.mp4")
    inputs = ["-f", "lavfi", "-i", f"color=c={BG}:s=1920x1080:d={seconds}:r=30"]
    if overlay_img:
        inputs += ["-i", overlay_img]
        fc = f"[0][1]overlay=x=220:y=(H-h)/2,{filters}[v]"
    else:
        fc = f"[0]{filters}[v]"
    run([*inputs, "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-r", "30",
         "-filter_complex", fc, "-map", "[v]", "-map", f"{2 if overlay_img else 1}:a",
         "-t", str(seconds), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", out])
    return out


def main():
    parts = []
    # 0) 콜드 오픈: 급제동 순간부터 (제목 전에 핵심 장면)
    parts.append(segment(0, COLD[0], COLD[1], 1, "급제동하면", "피코가 놀라요\n“앗, 깜짝 놀랐어!”"))
    # 1) 제목 카드: 앱이 그린 완성 단계 피코
    piko = os.path.join(TMP, "piko.png")
    run(["-ss", "126", "-i", SRC, "-frames:v", "1", "-vf", "crop=760:1100:860:140,scale=-1:600", piko])
    title = text_file("title.txt", "피코 드라이브")
    sub = text_file("sub.txt", "기어가 게임 규칙인 차량 전용 동승 펫")
    tag = text_file("tag.txt", "달릴 땐 말로만, 멈추면 화면으로.")
    parts.append(card(1, 4, (
        f"drawtext=fontfile={PIXEL}:textfile='{title}':expansion=none:fontcolor={WHITE}:fontsize=96:x=820:y=370,"
        f"drawtext=fontfile={FONT}:textfile='{sub}':expansion=none:fontcolor={GRAY}:fontsize=40:x=824:y=510,"
        f"drawtext=fontfile={FONT}:textfile='{tag}':expansion=none:fontcolor={AMBER}:fontsize=40:x=824:y=580"
    ), piko))
    # 2) 본편
    for i, (s, e, sp, t, b) in enumerate(SEGMENTS, start=2):
        parts.append(segment(i, s, e, sp, t, b))
    # 3) 수치 카드
    lines = text_file("nums.txt", "급제동 판단 6가지 패턴 · 오탐 0\n말하는 중 후진하면 0.36초 안에 무음\n주행 중 터치 가능한 화면 0")
    sdk = text_file("sdk.txt", "차량 신호 6종 · Pleos Vehicle SDK · Pleos TTS SDK · AAOS Car API")
    head = text_file("head.txt", "에뮬레이터 실측")
    src = text_file("src.txt", "근거와 재현 방법 — GitHub 저장소 qa/EVIDENCE.md")
    parts.append(card(99, 6, (
        f"drawtext=fontfile={FONTB}:textfile='{head}':expansion=none:fontcolor={WHITE}:fontsize=56:x=200:y=220,"
        f"drawtext=fontfile={FONT}:textfile='{lines}':expansion=none:fontcolor={WHITE}:fontsize=44:line_spacing=26:x=200:y=340,"
        f"drawtext=fontfile={FONT}:textfile='{sdk}':expansion=none:fontcolor={AMBER}:fontsize=34:x=200:y=640,"
        f"drawtext=fontfile={FONT}:textfile='{src}':expansion=none:fontcolor={GRAY}:fontsize=30:x=200:y=720"
    )))
    lst = os.path.join(TMP, "list.txt")
    with open(lst, "w") as f:
        f.writelines(f"file '{p}'\n" for p in parts)
    run(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", OUT])
    dur = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", OUT],
                         capture_output=True, text=True).stdout.strip()
    print(f"{OUT} {float(dur):.1f}s")


if __name__ == "__main__":
    main()
