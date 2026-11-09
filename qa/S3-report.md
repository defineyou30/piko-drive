# S3 차량 연동 QA 리포트 (2026-10-24)

환경: Pleos Connect 에뮬레이터 v2.1.0 (2560×1440, user 빌드) + Virtual Console (`tools/vconsole.py`)
재현: `tools/s3_e2e.sh qa/S3 probe`, `python3 tools/vconsole.py scenario thresholds|reverse-during-speech`

## 구현
| 파일 | 역할 |
|---|---|
| `app/.../vehicle/PleosVehicleGateway.kt` | 속도 AAOS PERF_VEHICLE_SPEED(m/s→km/h 여기서만), 기어 **Pleos Vehicle SDK 우선** → 실패 시 AAOS GEAR_SELECTION, UX 제한 CarUxRestrictionsManager, **1초 하트비트** 재방출, 바인더 스레드 안전(volatile 상태) |
| `app/.../voice/PleosSpeechSink.kt` | Pleos TTS ON_DEVICE. init 후 리스너, `speak(text, isFinal=true)`, onDone 대기(8초 상한), 취소 시 `stop()` |
| `app/.../PikoApp.kt` | 세션 수명 = 프로세스. Automotive면 Pleos, 아니면 시뮬레이터(+SIM 배지) |
| `app/.../EvidenceLog.kt` | `PIKO_EVIDENCE` 로그: 모드·주행 이벤트·발화 결과·표정·성장·트립 |
| `app/.../ui/live/LiveScreen.kt` | 모드 → 화면 (F1/F3/F3R/F6) 최소 배선 (본격 화면은 S4) |
| `core` | `PikoSession.events` 추가, 놀람(STARTLE) 발화는 30초 최소 간격 예외(시간당 상한은 적용) |

## E2E에서 발견·수정한 버그 (실차 환경에서만 드러난 것)
| # | 증상 | 원인 | 수정 |
|---|---|---|---|
| 1 | 출발 인사가 `DROPPED_FOCUS` | Pleos `speak(text, isFinal)`에 false → 스트리밍으로 간주, onDone 미발생 → 8초 타임아웃 | `isFinal=true` |
| 2 | 급제동 반응이 `DROPPED_BUDGET` | 출발 인사가 30초 전역 간격을 소진 | STARTLE은 최소 간격 예외 (테스트 추가, 시간당 12회 상한은 유지) |
| 3 | 도착 시 앱 크래시 | 증거 로그 `"%.1f".format(Int)` | 포맷 수정 |

## 증거
### 1. 전체 주행 E2E (`qa/S3/`, 녹화 `e2e.mp4`)
`BOOTING → PARKED → DRIVING → REVERSING → (DRIVING 48ms) → PARKED`, 발화 `GREETING SPOKEN → STARTLE SPOKEN → ARRIVAL SPOKEN`, 트립 리포트 1건, 후진 구간 발화 시도 0.
- 후진→P 사이 48ms DRIVING: P 직후 UX 제한이 아직 켜져 있어 **보수 정책(제한 중 P = 주행 정책)**이 적용된 것. 의도된 동작.

### 2. 감지 임계값 다패턴 (`qa/S3/thresholds/`)
| 패턴 | 입력 | 기대 | 결과 (판정값) |
|---|---|---|---|
| A | 60→36, 3초 (−8 km/h/s) | 미검출 | ✅ 미검출 |
| B | 60→24, 3초 (−12) | 검출 | ✅ HardBrake −11.4 |
| C | 100→40, 3초 (−20) | 검출 | ✅ HardBrake −19.1 |
| D | 100→76, 3초 (−8 @100) | 미검출 | ✅ 미검출 |
| E | 76→30, 2초 (−23, 짧음) | 검출 | ✅ HardBrake −26.0 |
| F | 30→72, 3초 (+14) | 검출 | ✅ HardAccel +13.8 |
| 가속 램프 4종 (+6~+12) | — | 미검출 | ✅ 오탐 0 |

### 3. 발화 중 후진 → 무음 (실제 앱, `qa/S3/reverse/evidence.log`)
| 시각 | 이벤트 |
|---|---|
| 40.196 | Pleos TTS 서비스 `stop(com.pikodrive)` |
| 40.197 | `mode REVERSING`, `speech INTERRUPTED STARTLE` |
| 40.200 | 플레이어 fadeout |
| 40.401 | 플레이어 stopped (**모드 전환 → 무음 205ms**) |
콘솔 R 적용 → 앱 수신 < 150ms (S0) 합산 **< 360ms**, 기준 1초 ✅

## 남은 것 / 이관
- 오디오 포커스 실패(타 앱 점유) 실차 재현: 에뮬레이터에서 유도 불가 → 코어 단위 테스트(재시도 1회 후 폐기)로 대체
- CAR_SPEED 권한 거부 시나리오: S5 엣지 플로우에서 화면 검증
- 실차에서는 성장이 느려(SPARK에서 시작) PT 데모용 "데모 성장 가속"을 **실차 데이터와 함께** 쓸 수 있어야 함 → S4 F9
- 앱 창 실측: 좌측 차량 패널 제외 약 1680×1215 px → S4 레이아웃 기준

---

## Codex 리뷰 반영 (`reviews/S3-codex.md`, 판정 NO-GO → 수정 후 재검증)
| # | 지적 | 조치 |
|---|---|---|
| 1 | 권한 거부 후에도 차량 세션 시작 | **설계 유지 + 명시화**: 거부 시에도 시작하되 게이트웨이가 속도 채널을 미지원으로 내보냄(코어 폴백). 권한 결과는 증거 로그에 기록. ※ 이 에뮬레이터는 `pm revoke` 후에도 CAR_SPEED가 자동 재부여돼 거부 경로 실측 불가 → 코드 경로로 보장 |
| 2 | callbackFlow 등록 예외가 세션을 종료 | 채널(speed/gear/ux)별 `guard` 격리. 실패 채널은 null/UNKNOWN, 하트비트 유지, `channel X FAILED` 증거 로그 |
| 3 | AAOS 폴백 중복 등록 | `AtomicBoolean`으로 1회만 등록, 해제도 등록된 경우만 |
| 4 | Car.disconnect 미호출 | `awaitClose`에서 disconnect |
| 5 | startSession 중복 생성 경합 | 메인 스레드 `starting` 플래그 (완료 시 해제) |
| 6·7·12 | TTS 늦은 콜백이 다음 발화 완료 / 동시 발화 / stop 경합 | 발화별 `Utterance` + onStart 받은 뒤에만 완료 인정, `speakLock`으로 직렬화 |
| 8 | SDK 예외 미변환 | 취소 외 예외 → false |
| 9 | release 순서 | stop → 대기 해제 → release |
| 10 | 혼합 스냅샷 | 갱신+스냅샷 생성을 한 락으로 |
| 11 | BOOTING 무기한 | **설계 유지**: 확인 전 보수 화면(F3)이 FRAMEWORK 3-3 안전 우선 부팅 규칙. 채널 실패는 증거 로그로 구분 |
| 13 | 로그에 대사 전문 | lineId만 기록 |
| 14 | events 드롭 가능 | **수용 안 함(문서화)**: 엔진 동작과 무관한 증거 보조 스트림, 버퍼 64로 실측 드롭 0 |

재검증: `qa/S3/rerun/` — 수정 후 동일 E2E에서 모드·발화(GREETING/STARTLE/ARRIVAL SPOKEN)·트립 정상, 크래시 0.

## S3 게이트: **통과** (2026-10-24, Claude)
필수 증거 3종 확보: ① D/P/R 전환 녹화(`qa/S3/e2e.mp4`) ② 이벤트→TTS 실발화 ③ 발화 중 후진 → 무음 205ms(+콘솔 지연 <150ms).
