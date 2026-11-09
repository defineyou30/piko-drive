# 시연 영상 v3 원본 (2026-10-26)
- 녹화: `python3 tools/record_v4.py qa/video4` (디버그 APK, Pleos 에뮬레이터 v2.1.0 + Virtual Console)
  - `a_input.mp4` 첫 실행·홈 브리핑·말로 입력·칩·상황 메모, `b_ride.mp4` 뒷좌석 루틴, `c_rear.webm` 뒷좌석 화면(보조 디스플레이) — 용량이 커서 git 제외
  - `marks.json` 장면 표식(녹화 기준 초), `evidence.log` 앱 증거 로그, `guardian.jsonl`·`guardian.png` 보호자 화면
- 합성: `python3 tools/make_video_v3.py qa/video4 submission/video/피코드라이브_시연영상.mp4` (141초)
- 벨트는 콘솔 주입 경로가 없어 대체 입력(화면 "시뮬레이션 입력" 배지). 앱 음성은 화면 녹화에 담기지 않아 실제 발화 문장을 자막으로 넣었다.
