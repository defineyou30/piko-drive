# S0 — Pleos Connect 에뮬레이터 설치 기록 (2026-10-24)

- 이미지: `system-images;android-34;pleos_car_emulator;arm64-v8a` (Pleos Connect v2.1.0, 42dot)
  - 출처: nexus-playground.pleos.ai `crp_car_arm64_emulator.zip` 4.67GB, SHA-1 `24d28eb2…bdccebb049419` 매니페스트와 일치
  - 42dot SDK 라이선스: 사용자 동의 후 설치 (2026-10-24)
- AVD: `Pleos_Connect` (2560×1440 @160dpi 가로, RAM 4GB, cold boot)
- 빌드: `Pleos/crp_avd_arm64/...release_connect_v2.1.0:user/test-keys` → **user 빌드**
- 부팅: 약 14초

## 첫 관찰 (S0 프로브 전)
- 시스템 UI 구조: 왼쪽 차량 패널(약 1/3 폭, 기어 P R N D·속도 표시) + 오른쪽 앱 영역 + 하단 공조/독 바
  → 앱 사용 가능 영역은 전체 2560×1440이 아님. 레이아웃은 앱 창 크기 기준으로 재검증 필요
- **adb VHAL 조회·주입 차단**: `cmd car_service get-property-value / inject-vhal-event` → "requires non-user build"
  → 차량 데이터 시뮬레이션은 공식 **Pleos Connect Virtual Console** 경유 필요 (설치 패키지에 `ai.pleos.connectwebconsole` 존재). S0에서 조사
- 설치된 Pleos 서비스: playground.service.location.fused / navi / adas, service.car, connecttokensampleapp 등
