# v3 아이 라이드 — 신호 프로브와 E2E (2026-10-26)

## 1. 신호 프로브 (`probe-cabin.log`)
Pleos Connect Emulator v2.1.0, probe 앱(`probeRear`, `probeCabin`), Virtual Console HTTP 주입.

| 신호 | 결과 |
|---|---|
| 뒷좌석 착석 ROW_2_L/R | 읽기·이벤트·주입 OK, 값 반전 (조수석과 같음) |
| 뒷좌석 벨트 | 읽기 OK, 콘솔 주입 경로 없음 → 데모 대체 입력 |
| 뒷문 위치 | 읽기·이벤트·주입 OK |
| 문 잠금 | 문별 영역 미지원, 4도어 묶음 영역으로 읽기·이벤트·주입 OK |
| 뒷창문 위치 | 읽기·이벤트·주입 OK (콘솔 0~100 → SDK 0~10) |
| 창문 잠금 | 읽기는 되나 콘솔 주입(`/vehicle/window-lock?area=`)이 SDK 값에 반영 안 됨 → 제외 |
| 실내 온도 | HVAC 현재 온도 ROW_1_LEFT 읽기 OK (19.1℃). 콘솔은 설정 온도만 바꿈 |
| 앰비언트 밝기 | 5좌석 묶음 영역으로 읽기·이벤트 OK. 쓰기는 `pleos.car.permission.CONTROL_CAR_LIGHTS` 필요 (CAR_LIGHTS·PRIVATE_CAR_LIGHTS만으로는 SecurityException) → 쉿 모드 30→15% 낮춤·복원 실측 |
| 차일드락 | SDK에 API 없음 |

## 2. E2E (`kidride/`)
`python3 tools/v3_kidride.py qa/v3/kidride` — 디버그 APK, 데모 가족(왼쪽 지우·초등, 오른쪽 콩이·유치원).
벨트만 디버그 브로드캐스트(데모 패널과 같은 대체 입력, 화면에 "시뮬레이션 입력 · 벨트" 배지), 나머지는 전부 콘솔 차량 신호.

| 장면 | 이벤트 → 발화 (`evidence.log`) | 화면 |
|---|---|---|
| 지우 탑승 → 스스로 벨트 | ChildBoarded → kid_board_school, BeltBuckled(self) + StickerEarned(SELF_BELT) → kid_belt_self_school | 01 |
| 콩이 탑승, 8초 미체결 | BeltPrompt → kid_belt_prompt_kinder, 체크 요약 "확인할 것 3개" (콩이 벨트·문 잠금·뒷창문 50%) | 02 |
| 미체결로 D | DepartUnbuckled → front_depart_unbuckled (운전자) | 03 |
| 주행 중 지우 벨트 풀림 | BeltReleasedWhileDriving → front_belt_released | 04 |
| 도착 | Arrived(오늘 준비물) → kid_arrive_items ×2 (두 번째는 대기 후 이어서) | 05 |
| 차도 쪽(왼쪽) 문 열림 | StreetSideDoorOpened → front_street_door | 06 |
| 콩이 인도 쪽 하차 | ChildAlighted(viaCurb) + StickerEarned(CURB_EXIT) → kid_bye_curb_kinder | 07 |
| 운전자 하차, 지우 남음 | LeftBehindAlert 1→2 (10초) → front_left_behind_1, 전체 화면 F10 | 08 |
| 지우 하차 | LeftBehindCleared → 재생 중이던 남김 알림 INTERRUPTED | 09 |

에뮬레이터 E2E에서 고친 문제: 세션 시작 직후 착석 값이 튀어 탑승·하차가 한 번씩 생기던 것(워밍업 3초), 도착 후 벨트 푸는 아이에게 벨트 재촉, 두 아이 대사가 겹치면 하나가 버려지던 것(1줄 대기열), 남김 해제 뒤에도 알림이 이어서 나오던 것.

## 2-1. 2주 차 추가 (같은 E2E)
| 항목 | 결과 |
|---|---|
| 쉿 모드 | `02a-quiet.png`. 증거 로그 `ambient dim 30→15`, `ambient restore →30`, Virtual Console 상태값도 15% → 30% |
| 보호자 알림 | `guardian.jsonl` 6건 (출발·도착·콩이 하차·지우 남김·지우 하차·확인 완료), 모두 code=200. 화면 `10-guardian-page.jpg` (tools/guardian_server.py) |
| 캐릭터 | `qa/v3/character/` — 램프 3칸, 1판(볼 램프), 2판(정수리 램프), 주행 화면 |

2주 차 E2E에서 고친 문제:
- **도착할 때마다 남김 오경보** (보호자 화면에서 발견): 주행 중 잠긴 문이 그대로 P에 들어오면 "잠긴 차 안에 아이"로 판정했다. 잠금 트리거를 "정차 중에 잠금"(false→true)으로 한정.
- 보호자 화면이 1초마다 다시 그려져 깜빡이던 것.
- 쉿 모드 조명 권한 (위 표).

## 3. 아직 아닌 것
- 운전석 착석 신호로 남김을 판정한다. 실차에서 운전자가 내리면 앱 프로세스·전원이 언제까지 사는지는 미확인.
- 벨트는 대체 입력으로만 시연했다 (SDK 경로는 코드에 등록돼 있음).
