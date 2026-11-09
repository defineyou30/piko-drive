# S2 코어 엔진 QA 리포트

- 작성 2026-10-24 · 범위 `:core` (순수 Kotlin/JVM)
- 실행: `./gradlew :core:test --console=plain` → **52 tests, 52 passed, 0 failed** (BUILD SUCCESSFUL) — Codex 리뷰 반영 후 재실행 (최초 40 + 리뷰 대응 12)
- 게이트 기준(시나리오 12종 이상 통과) 충족. 모든 시간 의존 테스트는 `runTest` 가상 시간 사용.

## 1. 테스트 목록

| # | 영역 | 테스트 | 결과 |
|---|---|---|---|
| 1 | 감지기 | 지속된 급제동은 1회만 판정 (판정 시점 17.8~18.2s 범위) | ✅ |
| 2 | 감지기 | 완만한 감속(-6 km/h/s)은 급제동 아님 | ✅ |
| 3 | 감지기 | **1샘플 노이즈 스파이크 미검출** (+ 디바운스를 끈 대조군은 오탐 → 가드가 실제로 동작함을 증명) | ✅ |
| 4 | 감지기 | **샘플 끊김(3초) 사이 60→20 은 급제동 아님** (+ 대조군) | ✅ |
| 5 | 감지기 | 끊김 직후 실제 급제동은 새 창으로 정상 검출 | ✅ |
| 6 | 감지기 | 급가속(+14 km/h/s) 검출 | ✅ |
| 7 | 감지기 | 부드러운 주행 스트릭 누적(1→2분) 및 급제동 후 리셋, 편안한 시간 = 주행 − 페널티 30초 | ✅ |
| 8 | 감지기 | 출발/정차 전환: P 1초(<3초)는 트립 유지, P 3초 유지 시 Parked 1회 | ✅ |
| 9 | 감지기 | **기어 미지원 폴백**: 속도 0 & 5초 → PARKED, 신호대기 3초는 DRIVING 유지 | ✅ |
| 10 | 감지기 | **BOOTING 유지**: 기어·속도 모두 없으면 20초가 지나도 BOOTING, 이후 P → PARKED (트립 아님) | ✅ |
| 11 | 감지기 | 앱이 주행 중 시작(BOOTING→D)되면 트립 시작 | ✅ |
| 12 | 감지기 | 후진: Reversing 이벤트, 후진 중 급가감속 이벤트 없음 | ✅ |
| 13 | 감지기 | 장거리(LongDrive) 단계별 발생 | ✅ |
| 14 | 감지기 | 역행/중복 타임스탬프 샘플 무시 | ✅ |
| 15 | 엔진 | **급제동 반응 지연**: 2.4초엔 아무것도 없음, 2.5초에 놀람 표정 + STARTLE 발화, 10초 후 CALM 복귀 | ✅ |
| 16 | 엔진 | 지연 중 R단 전환 시 반응 폐기 | ✅ |
| 17 | 엔진 | **REVERSING ⇒ 발화·표정·상태 변화 없음** | ✅ |
| 18 | 엔진 | **BOOTING ⇒ 무음** (쓰다듬기도 무시) | ✅ |
| 19 | 엔진 | **쿨다운**: 같은 카테고리 2분, 다른 카테고리는 통과, 2분 후 재허용 | ✅ |
| 20 | 엔진 | 정차 중엔 쿨다운 없음 | ✅ |
| 21 | 엔진 | **표정 레이트 리밋**: 주행 중 4초 뒤 변경 무시, 10초 뒤 허용 | ✅ |
| 22 | 엔진 | 정차 중엔 레이트 리밋 없음, 음성 off → QUIET | ✅ |
| 23 | 엔진 | **오디오 포커스 실패 → 4초 후 1회 재시도 성공** | ✅ |
| 24 | 엔진 | **포커스 2회 실패 → 정확히 1회 재시도 후 폐기 (10초 내)** | ✅ |
| 25 | 엔진 | 재시도 대기 중 R단 전환 시 재시도 취소 (INTERRUPTED) | ✅ |
| 26 | 엔진 | **성장 단계 해금**: 주행 중엔 단계·픽셀 고정, 정차 시 EYES 해금 | ✅ |
| 27 | 엔진 | 성장 곡선: 각 단계 해금 시점 픽셀 = pixelTarget, 상한 96 | ✅ |
| 28 | 엔진 | **데모 배수**: 실제 30초 × 360 = 180분 → BODY | ✅ |
| 29 | 엔진 | **트립 리포트 내용**: 소요 시간, 편안한 시간(배수 미적용), 놀란 순간 시각, 픽셀 증가, 단계 변화, 도착 인사 | ✅ |
| 30 | 엔진 | 음성 비활성 시 발화 없음 + QUIET + 로그 DROPPED_VOICE_OFF | ✅ |
| 31 | 대사 | 모든 대사 한글 14음절 이하 (≈3초) | ✅ |
| 32 | 대사 | 비난 단어(운전/못/위험/조심/왜/천천히…) 없음, lineId 고유 | ✅ |
| 33 | 대사 | 놀람 대사 순환 + 이벤트 종류별 매칭, 급제동은 지연 대상 | ✅ |
| 34 | 시뮬 | 10Hz 가상 시간 재생 + 선형 보간 + 완료 | ✅ |
| 35 | 시뮬 | capability off → gear UNKNOWN / ux null 로 마스킹 | ✅ |
| 36 | 시뮬 | gap·spike 재현 | ✅ |
| 37 | 시뮬 | commuteCompressed ≈ 90초, R 포함, P로 종료 | ✅ |
| 38 | 통합 | **commuteCompressed 전체 E2E**: 모드 BOOTING→P→D→R→P, 급제동 1회, 발화 순서 GREETING→COMFORT→STARTLE→ARRIVAL (전역 30초 예산 통과하도록 각본 재배치), 급제동(≈66.8s) +2.5초 발화, 후진 구간 발화 시도 0, SPARK→BODY, 저장소 반영 | ✅ |
| 39 | 통합 | 저장 상태 복원 시 성장 재계산 | ✅ |
| 40 | 저장 | InMemory 저장소: 최신순, 최대 개수 유지 | ✅ |
| 41 | 감지기 | UX 제한 중 P는 PARKED 아님(주행 정책), 해제 후 PARKED·Parked, 정차 중 제한 재개 시 트립 미시작 | ✅ |
| 42 | 감지기 | UX 지원 차량에서 UX 값 미수신 → BOOTING, 부팅 중 P+제한 → BOOTING | ✅ |
| 43 | 감지기 | 5초 미만 간격의 두 트립에서 급제동 각각 검출 (refractory 리셋) | ✅ |
| 44 | 감지기 | refractory 60초 설정이어도 새 트립에선 리셋 | ✅ |
| 45 | 감지기 | D→P 직전 마지막 구간 적립, R 구간은 미적립 | ✅ |
| 46 | 엔진 | D→R→D 가 지연 2.5초 안에 일어나면 지연 반응 폐기 | ✅ |
| 47 | 엔진 | 발화 중 R단 진입 → 코루틴 취소 + `SpeechSink.stop()` + INTERRUPTED | ✅ |
| 48 | 엔진 | 정차 발화 중 출발(P→D)·부팅 전환 시 stop | ✅ |
| 49 | 엔진 | 전역 예산: 카테고리 섞어도 30초 간격, 시간당 12회, 창 밀리면 재허용 | ✅ |
| 50 | 엔진 | 정차 중 발화는 주행 예산을 쓰지 않음 | ✅ |
| 51 | 통합 | `start()` 중복 호출 시 같은 Job·수집 1회, `stop()` 후 재시작 | ✅ |
| 52 | 통합 | 부팅 시 P+UX 제한 → BOOTING 유지, 쓰다듬기 무반응, 해제 후 PARKED에서 반응 | ✅ |

## 2. 설계 결정

1. **감지기는 순수 상태 기계** (`DrivingEventDetector.process(snapshot)`). 시간은 스냅샷 timestamp만 사용 → 결정적, 테스트가 쉬움. Flow 어댑터 `detect()` 별도 제공.
2. **엔진은 시간 의존 규칙(지연·쿨다운·레이트리밋·재시도)을 코루틴 `delay` + 주입 `Clock`으로 처리** → `runTest` 가상 시간에서 그대로 검증.
3. **기록과 반응 분리**: 트립 기록·편안한 시간 적립은 모든 모드에서 계속, 반응(표정·발화·기분 변화)은 DRIVING/PARKED에서만. → R/BOOTING 무음을 한 곳(`allowsReaction`)에서 강제.
4. **성장은 정차 시 확정**: 주행 중 `comfortMsTotal`만 쌓고 `stage`·`pixelCount`는 Parked에서 commit. 주행 중 성장 연출 금지(2-1-1)를 데이터 레벨에서 보장하고, F6 "늘어난 픽셀" 연출이 의미를 갖게 함.
5. **트립 종료 = P 진입 후 3초 유지** (F6 진입 조건). 3초 안에 다시 D면 같은 트립. 모드(`DriveMode.PARKED`)는 즉시 바뀌어 F1 UI는 바로 전환 가능.
6. **쿨다운은 발화 '수락' 시점 기준**: 포커스 실패로 폐기돼도 쿨다운 소모 → 내비 안내 중 재시도 폭주 방지 (보수적).
7. **발화 중 새 요청은 DROPPED_BUSY** (큐잉하지 않음) → 늦은 발화로 맥락이 어긋나는 것 방지.
8. **지연 반응은 모드가 바뀌면 폐기** (급제동 → 2초 안에 P/R 전환 시 놀람 대사 생략, 도착 리포트가 대신 보여줌).
9. **표정 레이트리밋 초과 요청은 폐기**, 기본 표정 복귀만 재시도 (R단이면 풀린 뒤 복귀).
10. 모든 발화 결과를 `speechLog`(최근 100건)로 남김 → PLAN 5장 안전 로그 증거로 사용 가능.
11. `PikoSession`이 Gateway→Detector→Engine 배선과 저장(표정만 바뀐 상태는 저장 생략)을 담당 → UI는 이 객체 하나만 보면 됨.

## 3. 임계값과 근거

| 항목 | 값 | 근거 |
|---|---|---|
| 급제동 | ≤ -10 km/h/s (≈0.28g) | 요청 기준. 텔레매틱스 보험 업계 통상 0.3~0.4g보다 약간 민감 → "놀람" 표현에 적합하고 정상 정지(-3~-6)와 충분히 분리 |
| 급가속 | ≥ +12 km/h/s (≈0.34g) | 일반 출발(+3~+6)과 분리, 전기차 순간 토크 고려해 제동보다 조금 높게 |
| 가속도 창 | 500ms 평균 | 속도 양자화(1 km/h @10Hz면 샘플간 미분이 ±10 km/h/s로 튐) 완화 |
| 지속 시간 | 400ms | 창과 합쳐 약 0.9초 유지돼야 판정 → 1~2샘플 스파이크 차단 |
| 비현실 값 | \|a\| > 45 km/h/s (≈1.3g) | 승용차 최대 제동 ≈1g. 그 이상은 센서 노이즈 |
| 최대 샘플 간격 | 1.5s | 이보다 긴 끊김은 창 초기화 (간격을 가로지르는 미분 금지). Gateway는 1초 heartbeat 계약 |
| 같은 종류 재판정 | 5s | 한 번의 긴 제동이 여러 이벤트로 쪼개지지 않게 |
| 기어 미지원 정차 | 속도 ≤0.5 & 5s | FRAMEWORK 3-3 |
| 정차 확정 | 3s | F6 진입 조건 |
| 편안함 페널티 | 급가감속 후 30s | 놀란 직후 시간은 "편안했던 시간"에서 제외 |
| 스트릭 단위 | 5분 (데모 32초) | 칭찬 발화가 2분 쿨다운보다 드물게 |
| 장거리 | 60분 | F5 |
| 반응 지연 | 2.5s | F4 "2~3초"의 중앙값, 결정적 테스트를 위해 고정 |
| 발화 쿨다운 | 2분/카테고리 (주행 중만) | 2-1-1 |
| 전역 발화 예산 | 주행 중 발화 간 최소 30초, 최근 1시간 최대 12회 | PLAN 5-2 "시간당 최대 N회". 평균 5분에 1회 이하 → 대화가 아닌 '가끔 한마디' |
| 표정 간격 | 10s (주행 중만), 일시 표정 유지 10s | 2-1-1 |
| 포커스 재시도 | 4s 후 1회, 10s 창 | F4 |
| 데모 성장 배수 | 360 | commuteCompressed 편안한 시간 ≈58초 × 360 ≈ 350분 → BODY(180분) 도달, COMPLETE(420분) 미만 (4단계는 S7 몫으로 남김) |

## 4. 알려진 한계

- **실차 데이터 미검증**: 임계값은 시뮬레이터 기준. S3에서 VHAL 실제 샘플링 주기·양자화 확인 후 튜닝 필요.
- **heartbeat 의존**: 기어 미지원 폴백/정차 확정은 샘플이 들어와야 평가됨. Pleos Gateway 구현 시 on-change 이벤트만 오면 1초 heartbeat 재방출을 넣어야 함 (인터페이스 KDoc에 계약 명시).
- **500ms 이상 지속되는 노이즈**(예: 0.5초간 20 km/h 드롭 후 복귀)는 판정될 수 있음. 반등(rebound) 검사는 미구현.
- **앱이 주행 중 시작되면** 트립·장거리 시간은 앱 시작 시점부터 계산.
- 주행 중 크래시 시 그 트립 리포트는 생성되지 않음 (편안한 시간 적립분은 상태 저장으로 보존, 다음 로드 시 성장 재계산).
- 엔진·감지기는 스레드 안전하지 않음 → 단일 스레드 스코프(Main)에서 사용해야 함.
- 대사 길이는 음절 수 근사(14음절)로만 검증. 실제 TTS 발화 시간은 S3에서 측정.
- **시계 일관성 (리뷰 보류 항목)**: 감지기는 스냅샷 timestamp, 엔진의 지연·쿨다운·로그는 주입 `Clock`을 쓴다. 실차에서 두 시계(예: VHAL 이벤트 시각 vs elapsedRealtime)가 어긋나면 리포트의 놀란 순간 시각과 발화 로그 시각이 역전될 수 있음. S3 Gateway에서 스냅샷 timestamp를 `Clock`과 같은 기준으로 맞출 것.
- **진행 중 트립 크래시 복구 (리뷰 보류 항목)**: `TripRecorder`·감지기 상태는 메모리에만 있다. 주행 중 크래시 후 재시작하면 그 트립은 새 트립(앱 시작 시점부터)으로 다시 시작되고 이전 구간의 리포트는 사라진다.
- UX 제한 중 P(restrictedPark)는 새 DriveMode 대신 DRIVING으로 매핑했다 (UI의 exhaustive `when` 보호). 이 상태에서는 성장 적립·급가감속 판정은 하지 않는다.

## 5. UI 연동 요약

```kotlin
val session = PikoSession.create(scope /*Main*/, gateway, speechSink, repository, demo = isDemo)
session.start()
session.mode         // StateFlow<DriveMode>   → F1/F3/F3R 화면 전환
session.pet          // StateFlow<PetState>    → 표정·단계·픽셀
session.tripReports  // SharedFlow<TripReport> → F6 (replay 1)
session.speechLog    // StateFlow<List<SpeechLogEntry>> → 안전 로그
session.engine.petByHand()           // F1 쓰다듬기 (PARKED에서만 반응)
session.stop()                       // 수집 중단 (start()는 멱등)
session.engine.voiceEnabled = false  // 권한 거부 → QUIET
session.engine.growthMultiplier = DEMO_GROWTH_MULTIPLIER // F9
SimulatedVehicleGateway(Scenarios.commuteCompressed)      // F9 SIM 재생
```

## 6. Codex 리뷰 반영 (`reviews/S2-codex.md`)

| # | 등급 | 지적 | 수정 | 검증 테스트 |
|---|---|---|---|---|
| 1 | 높음 | UX Restriction 미반영 | `DriveModeResolver`: UX 지원 차량은 `uxRestricted == false` 확인돼야 PARKED. 미수신이면 BOOTING 유지, 부팅 중 P+제한은 BOOTING, 그 외 P+제한은 DRIVING 정책(터치·쓰다듬기 차단, 쿨다운·예산 적용). 미지원 차량은 `uxRestricted == true`일 때만 차단. 트립 시작은 "P가 아닌 실제 이동"에서만. `DriveMode` 값은 추가하지 않음 | `ux restricted park is not PARKED until restriction lifts`, `ux supported but not yet received stays BOOTING`, `restricted park at boot keeps BOOTING and blocks petting` |
| 2 | 높음 | D→R→D 후 지연 반응 재생 | 모드 세대 토큰(`modeGeneration`) 캡처, 모드 변경 시 대기 중 지연 반응 Job 전부 취소 | `delayed reaction is dropped after D to R to D within the delay` |
| 3 | 높음 | 발화 중 R단 전환 시 계속 재생 | `SpeechSink.stop()` (기본 no-op) 계약 추가, `speak`는 취소 협조 의무. R·BOOTING 진입, P→D(UX 제한 진입 포함), 음성 off 시 speechJob 취소 + `stop()` + `INTERRUPTED` 로그. 재시도 대기도 함께 취소 | `speech in progress is stopped when entering reverse`, `parked speech is stopped when pulling away and when booting`, `pending retry is cancelled when mode switched to reverse` |
| 4 | 중간 | 트립 간 refractory 잔존 | `startTrip()`에서 `lastBrakeMs`/`lastAccelMs` 초기화 (변이 테스트로 수정 전 실패 확인) | `refractory resets between trips less than 5s apart`, `hard brake right after a new trip starts is not swallowed by previous refractory` |
| 5 | 중간 | D→P 마지막 구간 적립 누락 | 직전 샘플이 (제한 없는) D였으면 모드와 무관하게 그 구간을 적립. R 구간은 적립 안 함 | `final D segment before P or R is accrued but reverse time is not` |
| 6 | 중간 | `start()` 중복 수집 | `start()` 멱등 (실행 중이면 같은 Job 반환), `stop()` 추가 | `start is idempotent and stop halts collection` |
| 7 | 중간 | 전역 발화 상한 없음 | `SpeechBudget` (주행 중 최소 30초 간격·시간당 12회) 한 곳에서 판정, `DROPPED_BUDGET` 로그. 정차 발화는 예산 제외. 데모 각본은 예산을 지키도록 재배치(스트릭 32초, 급제동 66초) | `global speech budget spans categories - 30s gap and 12 per hour`, `parked speech does not consume driving budget`, `commuteCompressed demo end to end` |
| 8 | 낮음 | `onModeChanged` 외부 주입 가능 | `internal` 로 변경. 모드 입력은 `PikoSession`만 | (컴파일 수준 보장) |
| — | 보류 | 시계 일관성, 진행 중 트립 크래시 복구 | 요청에 따라 미수정, 4장 알려진 한계에 기록 | — |


---

## 7. Codex 재검증 (reviews/S2-codex-r2.md) 및 게이트 판정

| # | r2 판정 | Claude 결정 | 근거 |
|---|---|---|---|
| 1 | 부분 — 주행 중 UX 값이 null이면 DRIVING 유지 → 발화 가능 | **설계대로 유지 (반박)** | UX Restriction은 **시각·터치** 제한 신호다. 주행 중 발화(TTS)는 FRAMEWORK 3-4에서 허용된 유일한 채널이고, DRIVING 정책 자체가 가장 엄격한 시각 정책(터치 0·텍스트 0)이다. 값이 사라져도 더 위험한 쪽(PARKED)으로 풀리지 않으므로 안전 측 실패다. |
| 2 | 해결 | — | |
| 3 | 부분 — `SpeechSink.stop()` 기본 no-op | **S3 게이트 조건으로 이관** | 코어는 취소·stop 호출 계약을 보장한다. 실제 중단은 Pleos TTS 구현체 몫 → S3 필수 증거: "발화 중 R단 전환 시 1초 내 음성 중단" 화면녹화 + 로그 |
| 4~8 | 해결 | — | |
| 추가 높음 | 1번과 동일 지적 | 1번과 같이 반박 | |

**S2 게이트: 통과** (2026-10-24, Claude). 증거: 52개 테스트 전체 통과 (`./gradlew :core:test --rerun-tasks`), 시나리오 12종 기준 초과.
S3 이관 항목: SpeechSink.stop() 실구현·검증, 시계 일관성, 진행 중 트립 크래시 복구 여부 결정.

---

## 8. S7 신호 확장 — EV 배터리·충전구·조수석 (2026-10-24)

심사 피드백("차량 신호 활용이 적다") 대응. 공모 카테고리 "패밀리·동승자 케어"와 연결. `:core`만 변경, UI(`app/`)는 별도.

### 8-1. 계약 (Gateway가 채울 값)

| 위치 | 추가 | 기본값 |
|---|---|---|
| `VehicleSnapshot` | `batteryPct: Int?`, `chargePortOpen: Boolean?`, `passengerPresent: Boolean?` | 모두 `null` (기존 호출부 그대로 컴파일) |
| `VehicleCapabilities` | `battery: Boolean`, `passenger: Boolean` | `false`. `Full`은 기존 그대로(속도·기어·UX), 새 `All` = Full + 배터리·승객 |
| `PetState` | `batteryLow: Boolean` | `false`. 엔진 시작 시 항상 false로 초기화 (세션마다 차량 값으로 재판정) |
| `SimulatedVehicleGateway` | 기본 capabilities `Full` → **`All`**, 배터리·충전구는 `battery`, 착좌는 `passenger`로 마스킹 | |

### 8-2. 이벤트와 규칙

| 이벤트 | 판정 (감지기) | 반응 (엔진) |
|---|---|---|
| `BatteryLow(ts, pct)` | pct ≤ 20 진입 시 1회. 25 이상 회복해야 재무장(히스테리시스). 세션 첫 샘플이 이미 ≤ 20이어도 1회 | SLEEPY + ENERGY "low" 대사, 지연 없음. 주행 중엔 카테고리 쿨다운·전역 예산 적용. 상태 `batteryLow = true` |
| `BatteryRecovered(ts, pct)` | BatteryLow 이후 pct ≥ 25 | 반응 없음. `batteryLow = false`, 기본 표정 재계산 |
| `ChargingStarted(ts)` | 충전구 false/미수신 → true, **PARKED에서만** | HAPPY + ENERGY "charge" 대사. 엔진도 PARKED 아니면 무시(이중 가드) |
| `PassengerJoined(ts)` | 착좌 false → true. 첫 관측값 true는 무시, null은 '모름'(마지막 값 유지). **트립당 1회** (Parked 확정 시 재무장 → 출발 전 정차 구간 포함) | HAPPY + PASSENGER 대사, mood +2·bond +1. PARKED·DRIVING 허용(주행 중 예산 적용) |

- R·BOOTING: 기존 `allowsReaction` 규칙 그대로 → 세 신호 모두 발화·표정 없음. 단 `batteryLow` 상태 기록은 계속.
- **기본 표정**: 음성 off → QUIET > 정차 중 배터리 부족 → SLEEPY > CALM. 모드 전환·배터리 회복 시 일시 표정이 없으면 새 기본 표정으로 맞춤(주행 중엔 표정 레이트리밋 준수).
- 대사(14음절 이하, 운전자 비난 없음): ENERGY low "배터리가 졸려. 쉬어갈까?" / "나 살짝 졸려, 충전하러 갈까?", charge "충전 중이야, 든든해." / "냠냠, 전기 맛있어!", PASSENGER "옆자리 친구도 안녕!" / "같이 타니까 더 신나!"

### 8-3. 시나리오

- 빌더 스텝: `battery(pct: Int?)`, `chargePort(open: Boolean?)`, `passenger(present: Boolean?)` (계단식, `Keyframe`에 같은 이름 필드 추가).
- `commuteCompressed`는 **변경하지 않음** (발화 순서·전역 예산 단언이 깨지므로). 새 `Scenarios.familyEv` (~64초, `Scenarios.all`에 추가):
  t=0 P(배터리 32%) → t=3 동승자 탑승(PASSENGER) → t=6 출발(GREETING) → t=37 배터리 18%(ENERGY, 인사와 31초 간격) → t=38 스트릭 칭찬은 전역 예산으로 생략 → t=53 P, t=56 도착(ARRIVAL) → t=60 충전구 열림(ENERGY charge) → 정차 중 SLEEPY.

### 8-4. 테스트 (+15, 전체 **68 passed, 0 failed**)

| 파일 | 테스트 |
|---|---|
| `VehicleSignalDetectorTest` (5) | 20% 진입 1회·24%까지는 재무장 안 됨·25% 회복 후 재발생 / 저배터리 유지·null 샘플 시 반복 없음 / 충전은 정차 중만·열린 채 유지 시 반복 없음·null→true 허용 / 첫 관측 true 무시·false→(null)→true 발생 / 동승자 트립당 1회·트립 종료 후 재무장 |
| `VehicleSignalEngineTest` (8) | 정차 중 BatteryLow → SLEEPY+ENERGY, 쓰다듬기 후 SLEEPY 복귀, QUIET 우선, 회복 시 CALM / SLEEPY 기본 표정은 정차 중만 / 충전 반응은 정차 중만 / 동승자 HAPPY+PASSENGER / R·BOOTING 무음(상태 기록은 유지) / 주행 중 전역 예산·ENERGY 쿨다운 준수 / 저장된 batteryLow 초기화 / 대사 2종씩·순환·지연 없음 |
| `FamilyEvScenarioTest` (2) | familyEv E2E (이벤트 시각, 발화 순서 PASSENGER→GREETING→ENERGY→ARRIVAL→ENERGY, COMFORT DROPPED_BUDGET, 최종 SLEEPY) / capability off 마스킹·`All` 기본값 |

기존 대사 길이·비난 단어 테스트가 새 대사에도 그대로 적용되어 통과.

---

## 9. 기능 평가 반영 (2026-10-24)

기능 평가에서 나온 코어 결함 D2·D3·D6·D7 수정. `:core`만 변경, 기존 공개 이름 유지.

| # | 결함 | 수정 | 기준값 |
|---|---|---|---|
| D2 | D단 정지 상태(0 km/h)에서도 편안한 시간·스트릭이 쌓임 → 성장·COMFORT 발화 | 구간 시작 속도가 `DetectionConfig.movingKmh` 이상일 때만 편안한 시간·스트릭 적립. 트립 중 신호대기는 스트릭을 리셋하지 않고 '일시정지'. 장거리(LongDrive) 경과는 그대로 벽시계 기준. 속도 미지원(null) 차량은 기존 기어 기준 | `movingKmh = 5 km/h` |
| D3 | 움직임 없는 기어 조작(P→D→P, P→R→P 주차 조작)이 트립·리포트·도착 인사·'주행 횟수'가 됨 | **후보 트립**: D(UX 제한 P 제외)에서 5 km/h 이상 누적 이동이 `departConfirmMs`에 도달해야 `Departed`(=출발 인사). 그 전에 급가감속이 감지되면 그 시점에 즉시 확정(급출발이 트립 밖으로 새지 않게). 후진만으로는 시작 안 함. 확정 전 P 진입 시 누적 폐기 → `Parked`·리포트·ARRIVAL 없음. 확정 전 이동 시간은 편안한 시간으로 소급 적립(스트릭은 확정 시점부터 — 출발 인사와 칭찬의 전역 예산 30초 간격 유지). 속도를 한 번도 못 받은 차량은 기존처럼 D/R 진입 시 시작 | `departConfirmMs = 3_000` (누적, 정차 시 초기화) |
| D6 | 쓰다듬기 상한 미구현 | 정차 세션당 3회까지 기분·친밀도·AFFECTION 대사, 이후엔 HAPPY 표정만. 주행/후진 → PARKED 전환 시 카운터 초기화 | `PetConfig.pettingCapPerPark = 3` |
| D7 | 첫 만남 인사 없음 | `PetEngine.greetFirstMeet(): Boolean` (`session.engine.greetFirstMeet()`). PARKED에서만, 프로세스당 1회(전역 플래그, PARKED가 아니면 소모 안 함). GREETING `hello_1` "안녕, 난 피코야." + HAPPY. 주행 카테고리 쿨다운을 남기지 않아 곧바로 출발해도 출발 인사가 나옴. 출발 인사는 `greet_*`만 쓰도록 태그 분리 | — |

데모 영향: `Departed`가 D 진입이 아니라 이동 확정 시점으로 이동.
- `commuteCompressed`: 출발 인사 5s → ≈10s, 리포트 소요 시간 ≈79s → ≈74s. 발화 순서(GREETING→COMFORT→STARTLE→ARRIVAL)·급제동 시각·BODY 성장 동일.
- `familyEv`: 전역 예산 간격 유지를 위해 배터리 18% 37s → 41s, 정차 57s(확정 60s), 충전구 64s, 총 68s. 출발 확정 11s.

테스트 (+8, 전체 **76 passed, 0 failed**). 기존 테스트 중 새 규칙과 상충하는 6개는 기대값 갱신(부팅 직후 D 트립은 3초 이동 후 확정, 기어 미지원 폴백 출발은 5 km/h 이상 이동 필요, 후진만 한 조작은 트립 아님, UX 제한 테스트 이동 샘플 연장, 스트릭 테스트 편안한 시간 = 220s − 5 km/h 미만 1s − 페널티 30s, E2E 소요 시간).

| 파일 | 테스트 |
|---|---|
| `MovingTripDetectorTest` (5) | D 0 km/h 45초 → 이벤트·적립 0 / 트립 중 신호대기 45초는 편안한 시간·스트릭 미적립 / P→D 1초→P, 후진 주차 조작, 2초씩 끊긴 이동 → 트립 아님 / 출발 확정 시각(5 km/h 도달 + 3초), 급출발은 같은 샘플에서 Departed→HardAccel / 속도 미지원은 기어 기준 트립·적립 유지 |
| `PettingAndFirstMeetTest` (3) | 쓰다듬기 3회 후 표정만·주행 후 재정차 시 초기화 / 첫 만남 인사 PARKED 전용·1회·새 엔진에서도 무시·직후 출발 인사 정상 / 세션 E2E: 기어 토글·후진 조작만으로는 리포트·GREETING·ARRIVAL 없음 |
