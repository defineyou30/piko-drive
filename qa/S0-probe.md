# S0 프로브 결과 — Pleos Connect 에뮬레이터 v2.1.0 (2026-10-24)

## 결론: **Go** (2026-10-24, Claude)
핵심 루프(속도·기어 → 이벤트 판정 → TTS)에 필요한 데이터·출력이 전부 실제 Pleos 에뮬레이터에서 동작하고, 발화 중 후진 시 1초 내 중단도 선행 측정으로 확인했다.
Codex 리뷰(`reviews/S0-codex.md`)의 "조건부 No-Go" 근거 중 ① 실패 경로 표 ② 후진 중단 증거는 이 문서에 보강했고,
나머지(임계값 다패턴 검증, 하트비트 구현, 권한 거부 처리)는 S0가 아닌 **S3 구현·검증 항목**으로 아래에 이관했다.

## API별 결과
| 항목 | 경로 | 결과 | 근거 |
|---|---|---|---|
| 속도 | AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (0x11600207, m/s) | ✅ 수신 | 273 이벤트 / 30초, 간격 중앙값 131ms·p90 200ms. **값이 같아도 주기적으로 재전송** → 엔진 하트비트 요구사항 충족 |
| 급제동 재현 | Virtual Console 60→10 km/h 2.5초 (−20 km/h/s) | ✅ | 1초 창 최대 감속 −16 km/h/s 관측 (샘플링 손실) → 엔진 임계값 −10 km/h/s 로 검출 가능 |
| 기어 (표준) | AAOS `GEAR_SELECTION` (0x11400400) | ✅ | P=4, D=8, R=2. 전환 즉시 이벤트 |
| 기어 (Pleos) | Pleos Vehicle SDK 2.3.2 `getGear().registerGearSelection` | ✅ | `pleos.car.permission.CAR_DRIVE`(normal) 필요. 없으면 SecurityException |
| 벤더 기어 | `VENDOR_GEAR_SELECTION` 0x21400400 | ➖ 앱에 노출 안 됨 | 콘솔 내부용. 표준 GEAR_SELECTION 사용 |
| UX 제한 | `CarUxRestrictionsManager` | ✅ | D 진입 시 required=true active=80, P 복귀 시 false. **앱 매니페스트 `distractionOptimized=true` 필요** |
| TTS | Pleos TextToSpeech 3.3.1 `Mode.ON_DEVICE` | ✅ | 별도 앱 등록 불필요. 화자 seoyoon(ko_KR). speak→onStart **229ms**, stop()→onStop **3ms** (플레이어 페이드아웃 ~200ms) → S3 "발화 중 후진 1초 내 중단" 기준 충족 가능 |
| TTS 권한 | `pleos.car.permission.TTS_SERVICE` | normal | 없으면 SecurityException |
| HVAC | Vehicle SDK HVAC | ⏸ 미검증 | Should 기능. S7에서 필요 시 |
| NaviHelper | — | ⏸ 미검증 | Should(F5). S7에서 필요 시 |

## 실패 경로 (실측)
| 조건 | 결과 | 근거 로그 |
|---|---|---|
| `pleos.car.permission.CAR_DRIVE` 없음 | `SecurityException: getCurrentGearSelectionState need Permission` / `registerGearSelection need Permission` | 1차 실행 (권한 추가 전) |
| `pleos.car.permission.TTS_SERVICE` 없음 | `SecurityException: Permission pleos.car.permission.TTS_SERVICE is required` | 1차 실행 |
| TTS 리스너를 initialize 전에 등록 | SDK 내부 `UninitializedPropertyAccessException` | 2차 실행 |
| 콘솔에 소수 km/h 전송 | HTTP 400 `invalid_speed` | vconsole 1차 |
| 콘솔 P단 상태에서 이전 속도 잔존 | 속도 50km/h 유지된 채 P (콘솔이 P 전환 시 속도를 0으로 강제하지 않음) | 1차 실행 initial=13.89 m/s |
| `VENDOR_GEAR_SELECTION` 조회 | config 없음 (앱 비노출) | 전 실행 |

## 발화 중 후진 → 음성 중단 (S3 필수 증거 선행 측정)
`probe --ez reverseTest true` + `vconsole.py scenario reverse-stop`, 2회 실행.

| 구간 | 1회 | 2회 |
|---|---|---|
| 콘솔 R 적용 → 앱 GEAR_SELECTION=2 수신 | — (호스트 절대시각 미기록) | 호스트 적용·확인 로그(52.927)보다 기기 이벤트(52.786)가 먼저 → **< 150ms** |
| 앱 수신 → `stop()` → `onStop` 콜백 | 1ms | 6ms |
| 앱 수신 → 오디오 플레이어 `stopped` (페이드아웃 포함) | 212ms | 205ms |
| **합계 (콘솔 R → 무음)** | — | **< 360ms** (기준 1초) ✅ |

원본: `qa/S0/probe-reverse-stop-log.txt`
※ 이는 프로브가 기어 이벤트에서 직접 stop()을 부른 결과다. 실제 앱(PikoSession → SpeechSink.stop)은 S3에서 같은 방식으로 재측정한다.

## 함정·결정 사항
1. **Pleos SDK는 공개 Maven**: `https://nexus-playground.pleos.ai/repository/maven-releases` (`ai.pleos.playground:Vehicle:2.3.2`, `TextToSpeech:3.3.1`). 계정·토큰 불필요.
2. **속도는 Pleos SDK에 없다** → 표준 AAOS Car API(`useLibrary("android.car")`) + `android.car.permission.CAR_SPEED`(런타임 권한) 사용.
3. **TTS 리스너는 initialize 성공 후에 등록** (먼저 등록하면 SDK 내부 lateinit 예외).
4. **user 빌드라 adb VHAL 주입 불가** → 차량 데이터는 Virtual Console HTTP API로 주입. `tools/vconsole.py` 작성 (정수 km/h만 허용, 브라우저 세션 하트비트 1초 필요).
5. 앱 영역: Pleos 홈은 좌측 차량 패널 + 하단 공조 바가 상주. 앱 창 크기 기준 레이아웃 재검증 필요 (S4).

## S3 설계에 반영할 것 (Codex S0 리뷰 수용분)
1. **단위 변환 한 곳**: AAOS 속도는 m/s → `PleosVehicleGateway`에서만 ×3.6 해서 km/h로 넘긴다. 감지기·엔진은 km/h만 안다.
2. **하트비트 명시 구현**: 에뮬레이터는 100~200ms로 재전송하지만 실차 보장 없음 → 게이트웨이가 마지막 스냅샷을 최대 1초마다 재방출.
3. **임계값 재검증**: 콘솔 입력은 정수 km/h 계단 + 수신 간격 130~200ms. S3에서 감속 패턴 3종(−8 / −12 / −20 km/h/s) × 속도대 2종을 vconsole 시나리오로 돌려 오탐·미탐 표 작성.
4. **권한·UX 미확인 = 보수 모드**: CAR_SPEED 거부 → 속도 미지원 처리, CAR_DRIVE 거부 → 표준 GEAR_SELECTION 폴백, UX 값 미수신 → BOOTING 유지 (코어에 이미 구현).
5. **매니페스트**: `distractionOptimized=true`, 권한 4종 (CAR_SPEED·CAR_POWERTRAIN·pleos CAR_DRIVE·TTS_SERVICE).

## 재현 방법
```
adb forward tcp:18088 tcp:8088
adb install -r -g probe/build/outputs/apk/debug/probe-debug.apk
adb shell pm grant com.pikodrive.probe android.car.permission.CAR_SPEED
adb shell am start -n com.pikodrive.probe/.ProbeActivity
python3 tools/vconsole.py scenario probe
adb logcat -s PIKO_PROBE
```
원본 로그: `qa/S0/probe-drive-log.txt`, `qa/S0/probe-tts-log.txt`, `qa/S0/host-scenario.log`
