# 에셋 출처·라이선스

| 에셋 | 경로 | 출처 | 라이선스 | 비고 |
|---|---|---|---|---|
| Pretendard Regular/SemiBold/Bold 1.3.9 | app/src/main/res/font/pretendard_*.otf | https://github.com/orioncactus/pretendard (npm `pretendard@1.3.9`) | SIL OFL 1.1 | 앱 UI 서체 |
| Galmuri11 Bitmap Bold 2.40.3 | app/src/main/res/font/galmuri11_bold.ttf | https://github.com/quiple/galmuri (npm `galmuri@2.40.3`) | SIL OFL 1.1 | 피코 대사 전용. 작가 고지: 일부 글리프 디자인은 NDS 서체 영향 — 파일·코드는 OFL |
| Galmuri11 Bold (외곽선) 2.40.3 | tools/fonts/Galmuri11-Bold.ttf | 같은 출처 | SIL OFL 1.1 | 영상 합성 전용 |
| 피코 캐릭터 (픽셀 격자) | app/src/main/java/com/pikodrive/ui/piko/PikoSprite.kt | 자체 제작 (코드) | 프로젝트 소유 | AI 생성 아님 |
| 시연 영상 `submission/video/피코드라이브_시연영상.mp4` | 자체 제작 (앱 화면 녹화 + ffmpeg 자막) | 프로젝트 소유 | `tools/make_video.sh` |
| 픽셀 아이콘 | app/src/main/java/com/pikodrive/ui/component/PixelIcon.kt | 자체 제작 (코드) | 프로젝트 소유 | AI 생성 아님 |

## AI 생성 에셋 기록
| 에셋 | 모델 | 날짜 | 프롬프트 요약 | 수정 내역 |
|---|---|---|---|---|
| 키비주얼 배경 `submission/assets/keyvisual-base.png` | Codex (gpt-5.6-luna) 이미지 생성 | 2026-10-24 | 밤 도시 도로를 달리는 무브랜드 전기차 실내, 중앙 가로형 디스플레이는 빈 화면(#14110F), 앰버 무드등, 사실적 사진풍. 로고·엠블럼·글자·실존 차량 복제 금지 | 화면 영역에 앱 실제 렌더 화면(도착 리포트)을 합성 → `keyvisual.png`. 피코 캐릭터는 AI가 그리지 않음 |
| 키비주얼 배경 v2 `submission/assets/keyvisual4-base.png` | Codex (gpt-5.6-luna) | 2026-10-24 | 지하주차장에 정차한 무브랜드 전기차 실내, 정면 16:9 빈 디스플레이, 핸들·페달 프레임 밖, 테슬라식 디자인 금지 | 실제 Pleos 에뮬레이터 화면(P단·완성 피코) 합성 + "이미지 연출" 표기 → `keyvisual.png` (평가관 지적: 주행 중 텍스트 화면·테슬라풍 v1 폐기) |
