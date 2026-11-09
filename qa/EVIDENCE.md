# 피코 드라이브 — 안전·기술 증거 패키지 (v0.3)

> 기획서·PT·README에 인용하는 수치와 근거 모음. 모든 실측은 **Pleos Connect 에뮬레이터 v2.1.0**이며,
> 차량 데이터는 공식 Virtual Console(HTTP)로 주입했다. 벨트만 콘솔 주입 경로가 없어 디버그 빌드의 대체 입력을 썼다(화면에 "시뮬레이션 입력" 배지).
> 측정 기간 2026-10-24 ~ 10-26. 근거 열의 파일이 원본이다.

## 1. 쓰는 차량 API와 역할
| API | 출처 | 피코에서의 역할 | 실측 |
|---|---|---|---|
| 뒷좌석 착석 ROW_2_L/R, 운전석 착석 | **Pleos Vehicle SDK** `Seat.registerSeatOccupancy` | 탑승·하차, 아이별 체크, 남김 판정 | ✅ 읽기·이벤트·주입. 값이 반대로 옴(착석=false) → 게이트웨이 한 곳에서 반전 |
| 뒷좌석 벨트 | `Seat.registerSeatBeltBuckledState` | 벨트 체크·출발 경고·주행 중 풀림 | ✅ 읽기. 콘솔 주입 경로 없음 → 시연은 대체 입력 |
| 뒷문 위치 | `Door.registerDoorPosition` | 차도 쪽 문 경고, 인도 쪽 하차, 주행 중 문 열림 | ✅ |
| 문 잠금 (4도어 묶음 영역) | `Door.registerDoorLockState` | 출발 전 요약, "정차 중 잠금" 남김 트리거 | ✅ (문별 영역은 미지원) |
| 뒷창문 위치 | `Window.registerWindowPosition` | 출발 전 요약 | ✅ (콘솔 0~100 → SDK 0~10) |
| 실내 온도 | `HVAC.registerHvacTemperatureCurrent` (ROW_1_LEFT) | 영유아 동승 시 체크 | ✅ 읽기 (콘솔은 설정 온도만 바꿈) |
| 앰비언트 밝기 (5좌석 묶음) | `Light.setAmbientLightBrightness` | 쉿 모드 조명 낮춤·복원 | ✅ 30→15→30 (권한 `CONTROL_CAR_LIGHTS` 필요) |
| 기어 | `Gear.registerGearSelection` (실패 시 AAOS `GEAR_SELECTION`) | 화면 모드(정차=브리핑·입력, 주행=얼굴만, 후진=정지·무음), 출발·도착 판정 | ✅ |
| 속도 | AAOS `PERF_VEHICLE_SPEED` | "움직이는 중" 판정(5 km/h), 아이 동승 주행 시간 | ✅ |
| UX 제한 | AAOS `CarUxRestrictionsManager` | 제한 중엔 정차여도 주행 정책 | ✅ |
| 탑승자 구역 | AAOS `CarOccupantZoneManager` | 뒷좌석 디스플레이 찾기 (없으면 보조 디스플레이, 그것도 없으면 음성만) | 보조 디스플레이로 ✅, 실차 뒷좌석 구역은 미검증 |
| 음성 출력 | **Pleos TextToSpeech** 3.3.1 | 주행 중 유일한 소통 채널 | ✅ |
| 음성 인식 | **Pleos SpeechToText** 3.3.1 ON_DEVICE | 정차 중 말로 입력 | ✅ 합성 음성 주입 (§5) |
| 쓰지 않는 것 | 차일드락(API 없음), 창문 잠금(콘솔 주입이 SDK에 반영 안 됨 → 오표시 위험) | | `qa/v3/README.md` |

신호 프로브 원본: `qa/v3/probe-cabin.log`, `qa/v3/README.md`, 프로브 코드 `probe/.../ProbeActivity.kt`.

## 2. 운전 안전 원칙과 수치
| 항목 | 기준 | 실측·근거 |
|---|---|---|
| 주행 중 터치 가능한 화면 | 0 | 0 — 주행 화면은 피코 얼굴만 (v0.2 측정 `qa/S5/restart/`, v0.3 화면 구조 동일) |
| 주행 중 텍스트 | 0 | 0 |
| 발화 중 후진 → 무음 | ≤ 1초 | ≤ 360ms (`qa/S3/reverse/evidence.log`, 음성 엔진 동일) |
| 운전자 안전 알림 | 다른 말보다 우선 | 진행 중인 아이 대사·메모를 끊고 말한 뒤, 끊긴 메모는 다시 말함 (코어 테스트 `RideSpeechTest`) |
| 아이 대사·메모 | 주행 중 발화 예산(30초 간격) 준수, 겹치면 3줄 대기 | 코어 테스트, E2E `DROPPED_COOLDOWN` 실측 |
| 남김 보조 알림 | 10초마다 반복(3단계), 상황이 풀리면 재생 중 알림도 끊음 | `qa/v5/kidride/evidence.log` (level 1→2, `INTERRUPTED`) |
| 센서 미수신 | "확인 불가" (정상으로 간주하지 않음) | 코어 테스트 `unknown signals show as unknown, never ok`, `missing rear occupancy…` |
| 도착 시 자동 잠금 상태 | 남김으로 보지 않음 (정차 중 새로 잠갔을 때만) | 코어 테스트 + 보호자 화면 E2E에서 오경보 발견·수정 (`qa/v3/README.md`) |
| 마이크 권한 | 첫 실행에 묻지 않음 (안전 세션 시작을 막지 않게) | v5 E2E에서 발견·수정 (`qa/v5/README.md`) |

## 3. 루틴 E2E (앞 화면, 실측)
`tools/v3_kidride.py` — 데모 가족(왼쪽 지우·초등, 오른쪽 콩이·유치원), 데모 일정·메모, 보호자 알림 켜짐.

| 장면 | 이벤트 → 발화 |
|---|---|
| 일정 브리핑 | 일정 15분 전 창 → `agent_schedule` |
| 탑승 · 스스로 벨트 | ChildBoarded → `kid_board_*`, BeltBuckled + StickerEarned → `kid_belt_self_*` · 메모(탈 때) `agent_memo_board` |
| 벨트 미체결 8초 | BeltPrompt → `kid_belt_prompt_kinder` |
| 미체결로 D | DepartUnbuckled → `front_depart_unbuckled` |
| 주행 중 벨트 풀림 | BeltReleasedWhileDriving → `front_belt_released` |
| 도착 | Arrived(그 무렵 일정 준비물: 지우 도복·띠, 콩이 수영 가방) → `kid_arrive_items_*` ×2, 메모(도착) |
| 차도 쪽 문 | StreetSideDoorOpened → `front_street_door` |
| 인도 쪽 하차 | ChildAlighted(viaCurb) + 스티커 → `kid_bye_curb_*`, 메모(내릴 때) `agent_memo_alight` |
| 운전자 하차, 아이 남음 | LeftBehindAlert 1→2 → `front_left_behind_1`, 전체 화면 F10 |
| 하차 | LeftBehindCleared → 재생 중 남김 알림 중단 |
| 보호자 알림 | 출발·도착·하차·남김·하차·확인 완료 6건 code=200 (`guardian.jsonl`, 로컬 웹훅 서버). 앱은 HTTPS 주소만 보냄(시연용 127.0.0.1·localhost만 예외) |
| 쉿 모드 | 앰비언트 30→15→30 |

근거: `qa/v5/kidride/`(최신), `qa/v4/agent/`, `qa/v4/copyfix/`.

## 4. 뒷좌석 화면
보조 디스플레이(`adb emu multidisplay add`)에서 `RearActivity` 실행, 탑승 말풍선·스티커 점등·주행 중 표시 (`qa/v4/rear/`).
한계: 이 에뮬레이터 이미지는 보조 디스플레이를 붙이면 몇 분 안에 시스템 앱이 연달아 종료 → 시연은 짧게. 실제 Pleos 차량의 뒷좌석 화면 유무는 미확인.

## 5. 말로 입력 (Pleos SpeechToText)
에뮬레이터엔 마이크 입력이 없어 macOS 합성 음성(16kHz PCM)을 SDK `sendAudio`로 주입 (`qa/v4/stt-probe.md`, `qa/v5/README.md`).
- 요일·시각: 6회 모두 인식 ("세시" → "3시" 정규화)
- 일정 제목 "태권도": 6회 중 2회 정확 (나머지 "체크인도"·"퇴근했는데도") → 칸을 비워 두고 칩으로 확정, 제목이 없으면 저장 잠김
- 엔진 오류(ON_DEVICE_ENGINE_ERROR) 간헐 발생 → 1회 자동 재시도
- 미검증: 실제 차량 마이크·사람 목소리

## 6. 테스트·빌드
| 항목 | 결과 |
|---|---|
| 코어 단위 테스트 | 135개 통과 / 실패 0 (`./gradlew :core:test`) |
| 릴리스 APK | v0.3.0, 자체 서명 키, 디버그 전용 시험 훅(벨트 대체 입력 등) 미포함 — `qa/v5/release-apk.txt` |

## 7. 지원·미지원 매트릭스
| 기능 | 에뮬레이터 실측 | 단위 테스트 | 비고 |
|---|---|---|---|
| 뒷좌석 루틴·남김·출발 전 체크 | ✅ | ✅ | 벨트는 대체 입력 |
| 일정 브리핑·상황 메모·안전 기록 | ✅ | ✅ | |
| 칩 입력·초안 저장 | ✅ | | |
| 말로 입력 | ✅ 합성 음성 | ✅ 해석기 | 실제 마이크 미검증 |
| 뒷좌석 화면 | ✅ 보조 디스플레이 | | 실차 미검증, 에뮬레이터 불안정 |
| 보호자 알림 | ✅ 로컬 웹훅 서버 | | HTTPS만 허용, 푸시·계정은 후속 |
| 아이 삭제 시 그 아이의 일정·메모 함께 삭제 | ✅ (데모 가족 콩이 삭제 → 일정 2→0) | | |
| 쉿 모드 조명 | ✅ | | |
| 실차: 착석 값 방향, 운전자 하차 후 앱 동작 시간 | ❌ | | 후속 과제 |
