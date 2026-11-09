## 높음

### 1. UX Restriction을 전혀 반영하지 않아 주행 중 화면·발화가 허용될 수 있음

- 위치: `core/src/main/kotlin/com/pikodrive/core/model/Model.kt:10-15`, `core/src/main/kotlin/com/pikodrive/core/driving/DriveModeResolver.kt:17-29`, `core/src/main/kotlin/com/pikodrive/core/pet/PikoSession.kt:46-50`
- `VehicleSnapshot.uxRestricted`가 전달되지만 모드 판정과 `PetEngine` 반응 제한에는 사용되지 않습니다.
- `uxRestricted = true`, `gear = PARK` 또는 `DRIVE`인 스냅샷에서도 정상적인 `PARKED`/`DRIVING`으로 판정되어 발화·표정 반응이 실행됩니다.
- FRAMEWORK 3-3의 “UX Restriction 확인 전 안전 우선 부팅” 및 권한/제한 정책과 불일치합니다.
- 실패 시나리오: 앱 재시작 직후 기어는 P로 먼저 전달되고 UX Restriction은 아직 `true`인데, 엔진이 `PARKED`로 전환되어 도착 인사나 쓰다듬기 발화를 실행합니다.
- S3 전 API를 `DriveMode`와 별도 `SafetyGate` 또는 `VehicleSafetyState(gear, uxRestriction, dataFreshness)`로 분리하고, `uxRestricted == true` 또는 미확인 상태에서는 반드시 무음·비대화 모드가 되도록 해야 합니다.
- 테스트 공백: `uxRestricted = true`에서 발화·표정·쓰다듬기가 차단되는 테스트가 없습니다.

### 2. F4 지연 발화가 R단을 거쳐 다시 D로 돌아오면 재생될 수 있음

- 위치: `core/src/main/kotlin/com/pikodrive/core/pet/PetEngine.kt:82-86`
- 지연 작업은 발화 시점에 `_mode.value == modeAtEvent`만 검사합니다.
- 실패 시나리오: D에서 급제동 발생 → 1초 후 R → 1초 후 다시 D → 2.5초 시점에 모드가 D이므로 과거 이벤트의 표정·TTS가 실행됩니다. R단 동안의 안전 상태 전환이 무효화되지 않았습니다.
- F4의 “지연 중 R단 전환 시 폐기”를 만족하지 못합니다.
- `modeGeneration` 또는 주행 세션 토큰을 두고, 지연 예약 시 토큰을 캡처한 뒤 모드가 한 번이라도 바뀌었으면 폐기해야 합니다. `onModeChanged`에서 지연 반응과 재시도 작업도 취소하는 편이 안전합니다.
- 현재 테스트는 R단 전환 후 계속 R에 머무는 경우만 검증합니다: `core/src/test/kotlin/com/pikodrive/core/pet/PetEngineTest.kt:175-185`.

### 3. 발화 중 R단 전환 시 `SpeechSink.speak()`가 계속 진행될 수 있음

- 위치: `core/src/main/kotlin/com/pikodrive/core/pet/PetEngine.kt:153-174`, `core/src/main/kotlin/com/pikodrive/core/pet/Speech.kt:39-46`
- 모드 재확인은 발화 시작 전과 재시도 직전에만 수행합니다. 이미 `sink.speak(request)`에 들어간 뒤 R단으로 바뀌어도 취소 계약이 없습니다.
- 실패 시나리오: 오디오 포커스를 얻은 직후 차량이 R로 전환되면 TTS가 완료될 때까지 후진 중 음성이 출력될 수 있습니다.
- `SpeechSink`가 `suspend fun speak()`만 제공해 중단·취소·포커스 반환을 보장하지 않습니다.
- S3 API는 `SpeechSink.speak(request, safetyToken)` 또는 `SpeechSession` 형태로 설계해 모드 전환 시 취소 및 오디오 포커스 반환을 보장해야 합니다. `SpeechSink` 내부에서도 R단 전환을 관찰할 수 있어야 합니다.
- 테스트 공백: 발화가 진행 중인 상태에서 R단으로 바뀌는 시나리오가 없습니다.

## 중간

### 4. 새 트립 시작 시 이전 트립의 급제동·급가속 refractory 상태가 남음

- 위치: `core/src/main/kotlin/com/pikodrive/core/driving/DrivingEventDetector.kt:71-80`, `82-89`
- `startTrip()`은 `lastHardEventMs`는 초기화하지만 `lastBrakeMs`와 `lastAccelMs`는 초기화하지 않습니다.
- 실패 시나리오: 첫 트립에서 t=10초에 급제동 → 3초 후 P에서 트립 종료 → 1초 뒤 다시 D → 새 트립에서 급제동 발생. 새 이벤트가 이전 급제동으로부터 5초 이내라서 `onSustainedAccel()`에서 조용히 무시됩니다.
- 실제로는 트립이 바뀌었으므로 refractory 기준도 새로 시작해야 합니다.
- `startTrip()`에서 두 종류의 마지막 이벤트 시각을 모두 초기화하거나, refractory 상태를 트립 ID와 함께 관리해야 합니다.
- 테스트 공백: 트립 간격이 5초 미만인 동일 유형 급제동/급가속 테스트가 없습니다.

### 5. D에서 P로 전환되는 마지막 주행 구간의 편안한 시간이 누락됨

- 위치: `core/src/main/kotlin/com/pikodrive/core/driving/DrivingEventDetector.kt:49-57`, `94-117`
- 시간 적립은 `newMode == DRIVING`일 때만 실행됩니다.
- 실패 시나리오: 마지막 D 샘플이 t=10,000ms이고 P 샘플이 t=12,000ms이면, t=10,000~12,000ms의 정상 주행 시간이 적립되지 않습니다. 이후 `Parked`에서 `comfortPendingMs`만 flush하므로 누락된 2초는 복구되지 않습니다.
- F6의 편안한 시간, 성장 누적, 픽셀 증가량이 실제보다 작아집니다.
- 모드 전환 시 이전 모드가 DRIVING이면 P/R 전환 직전까지의 유효한 `dt`를 별도로 적립해야 합니다. 다만 R 구간을 편안한 주행으로 포함할지는 명시적인 정책으로 분리해야 합니다.
- 테스트 공백: 마지막 D 샘플과 P 확정 샘플 사이의 시간 적립량을 검증하지 않습니다.

### 6. `PikoSession.start()` 중복 호출 시 차량 스트림과 저장 수집이 중복 실행됨

- 위치: `core/src/main/kotlin/com/pikodrive/core/pet/PikoSession.kt:36-50`
- `start()` 호출마다 새로운 코루틴과 `gateway.snapshots.collect`가 생성됩니다. 이미 시작했는지 검사하거나 기존 Job을 반환하지 않습니다.
- 실패 시나리오: UI 재생성 또는 lifecycle 재진입으로 `session.start()`가 두 번 호출되면 같은 스냅샷이 detector에 두 번 전달됩니다. 두 번째 스트림은 타임스탬프 중복으로 대부분 무시되지만, 저장 collector와 엔진 모드 전파가 중복되고 구현체에 따라 경쟁 상태가 발생합니다.
- S3에서 차량 연결 재시작과 화면 lifecycle이 추가되면 재현 가능성이 높습니다.
- `start()`를 idempotent하게 만들고 `startJob`을 보관하거나, 명시적인 `start/stop` lifecycle API와 단일 수집 supervisor를 제공해야 합니다.
- 테스트 공백: `start()` 두 번 호출, `stop()` 후 재시작, Gateway 재연결 시나리오가 없습니다.

### 7. 전역 발화량 상한이 없어 장시간 주행에서 안전 예산을 초과할 수 있음

- 위치: `core/src/main/kotlin/com/pikodrive/core/pet/PetEngine.kt:153-175`
- 쿨다운은 카테고리별로만 적용되고, `speechJob`이 끝난 뒤에는 다른 카테고리 발화가 곧바로 허용됩니다.
- 실패 시나리오: COMFORT 발화 직후 REST, STARTLE, ARRIVAL 이벤트가 연속 발생하면 각 카테고리가 모두 발화할 수 있습니다. 카테고리별 2분 제한은 지키더라도 전체 시간당 발화 횟수 제한은 보장되지 않습니다.
- `qa/S2-core-report.md:97`도 이 한계를 명시하고 있습니다.
- S3 전 `SpeechBudget`을 별도 정책 객체로 만들고, 전체 발화 횟수·총 발화 시간·운전 모드별 상한을 단일 장소에서 결정해야 합니다.
- 테스트 공백: 여러 카테고리가 교차하는 장시간 이벤트 스트림에 대한 전역 발화량 검증이 없습니다.

## 낮음

### 8. `onModeChanged()`가 외부에서 임의의 안전 상태를 주입할 수 있음

- 위치: `core/src/main/kotlin/com/pikodrive/core/pet/PetEngine.kt:68-70`
- `onModeChanged(DriveMode.PARKED)`를 호출하면 실제 차량 스냅샷이나 UX Restriction 확인 없이 즉시 정차 상태가 됩니다.
- 실패 시나리오: S3 어댑터의 순서 오류로 D 상태에서 P 모드가 먼저 전달되면 `petByHand()`와 정차 중 발화가 활성화됩니다.
- 현재 API는 “차량 사실”과 “엔진에 적용할 정책 상태”를 분리하지 않아 호출 순서에 의존합니다.
- S3에서는 `PetEngine.onModeChanged()`를 공개하지 말고, `VehicleSnapshot` 또는 검증된 `VehicleSafetyState`만 단일 입력으로 받도록 변경하는 것이 안전합니다.
- 테스트 공백: 잘못된 모드 순서와 차량 데이터 불일치 입력을 검증하지 않습니다.

## 테스트·검증 관련 추가 공백

- 현재 `qa/S2-core-report.md`는 40개 테스트 통과를 기록하지만, 현재 환경에서는 Gradle wrapper가 `~/.gradle/...zip.lck` 파일 접근 거부로 실행되지 않았습니다. 따라서 이 리뷰에서는 기존 QA 리포트의 결과를 재실행해 확인하지 못했습니다.
- 동시성 테스트가 없습니다. `PetEngine`과 `DrivingEventDetector`가 스레드 안전하지 않다는 전제만 있고, 실제로 잘못된 dispatcher에서 호출했을 때의 방어가 없습니다.
- 시스템 시계와 스냅샷 시계의 일관성 테스트가 없습니다. `DrivingEventDetector`는 스냅샷 timestamp를 사용하지만 `PetEngine`의 지연·쿨다운은 `Clock`을 사용하므로 두 시계가 어긋나면 로그 시각과 이벤트 시각이 역전될 수 있습니다.
- `TripRecorder`는 크래시 복구 시 진행 중 트립을 복원하지 못합니다. S3 전에 진행 중 트립 상태와 마지막 차량 안전 상태를 영속화할지 API 수준에서 결정해야 합니다.