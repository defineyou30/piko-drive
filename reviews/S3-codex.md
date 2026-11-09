## S3 코드 리뷰

### 1. 치명적: 권한 거부 후에도 차량 세션을 시작함

- 파일: `MainActivity.kt:39-40, 51-53`
- 시나리오: `CAR_SPEED` 권한을 거부하면 `RequestMultiplePermissions` 콜백이 권한 결과를 확인하지 않고 무조건 `start()`를 호출한다. 이후 `PleosVehicleGateway`가 생성되고 차량 콜백 등록을 시도한다.
- 결과: 속도 권한 거부 상태에서도 차량 모드로 진입하며, SDK/Car API가 `SecurityException`을 던지면 세션 수집이 종료되거나 앱이 비정상 상태가 된다.
- 심각도: **Critical**
- S4 전 조치: 권한 결과가 모두 허용된 경우에만 차량 세션을 시작하고, 거부 시 조용한 제한 모드 또는 SIM/BOOTING 상태를 명시해야 한다.

### 2. 치명적: `callbackFlow` 초기화 중 예외가 세션 전체를 종료시킴

- 파일: `PleosVehicleGateway.kt:73-75, 99-109, 111-118, 138-143`
- 시나리오: `registerCallback`, `getCarManager`, Pleos 기어 리스너 등록, UX 리스너 등록 중 권한 거부나 SDK 미설치로 예외가 발생한다.
- 결과: Pleos 기어 등록 실패만 `runCatching`으로 처리되지만, 속도·AAOS 기어·UX 등록은 개별 보호가 없다. `callbackFlow`가 예외로 닫히고 `PikoSession`의 수집 코루틴도 종료된다. 코어 계약의 “미지원 기능 자동 비활성”과 맞지 않는다.
- 심각도: **Critical**
- S4 전 조치: 각 채널을 독립적으로 실패 처리하고, 실패한 채널은 `null/UNKNOWN`으로 유지하되 다른 채널과 heartbeat는 계속 동작시켜야 한다.

### 3. 높음: Pleos 기어 실패 콜백에서 AAOS 리스너가 중복 등록될 수 있음

- 파일: `PleosVehicleGateway.kt:94-97, 106-109, 138-143`
- 시나리오: 초기 등록 실패로 이미 AAOS 폴백을 등록했는데, 이후 Pleos SDK의 `onFailed`가 다시 호출된다.
- 결과: 동일한 AAOS 콜백이 중복 등록되어 하나의 기어 이벤트가 여러 번 전달되거나, `awaitClose`에서 등록 횟수와 해제 횟수가 맞지 않을 수 있다.
- 심각도: **High**
- S4 전 조치: 기어 소스 전환 상태를 원자적으로 관리하고, AAOS 폴백 등록을 단 한 번만 허용해야 한다.

### 4. 높음: 차량 연결 수명 관리가 실제로 완료되지 않음

- 파일: `PleosVehicleGateway.kt:38-40, 128-147`, `PikoApp.kt:35, 47-50`
- 시나리오: 세션 중단, 앱 재시작, 세션 재초기화 또는 Activity/프로세스 수명 변화가 발생한다.
- 결과: `awaitClose`에서는 콜백과 Pleos 객체를 해제하지만 `Car.disconnect()`는 호출하지 않는다. `disconnect()` 메서드는 존재하지만 호출자가 없다. Car 연결과 관련 manager가 프로세스 수명 동안 남는다.
- 심각도: **High**
- S4 전 조치: 세션 종료 시 `gateway`를 닫는 명시적 수명 계약을 추가하고, `Car.disconnect()`까지 반드시 실행해야 한다.

### 5. 높음: `PikoApp.startSession()`의 중복 호출 방지가 원자적이지 않음

- 파일: `PikoApp.kt:37-50`
- 시나리오: Activity 재생성, 권한 콜백, 빠른 재진입으로 `startSession()`이 두 번 호출된다.
- 결과: 두 코루틴이 모두 `_session.value == null`을 확인한 뒤 차량 Gateway, TTS, PikoSession을 각각 생성한다. 한쪽 세션만 `_session`에 저장되고 다른 쪽의 차량 콜백·TTS·EvidenceLog가 누수될 수 있다.
- 심각도: **High**
- S4 전 조치: 생성 중 상태를 별도로 두거나 `Mutex/atomic`으로 세션 생성을 직렬화해야 한다.

### 6. 높음: TTS 콜백이 이전 발화의 결과를 다음 발화에 전달할 수 있음

- 파일: `PleosSpeechSink.kt:30-40, 67-80`
- 시나리오: 발화 A가 `stop()` 또는 타임아웃된 직후 발화 B가 시작되고, A의 늦은 `onDone/onStop/onError`가 도착한다.
- 결과: 모든 콜백이 공유하는 `current`를 참조하므로 A의 콜백이 B의 `CompletableDeferred`를 완료시킬 수 있다. B가 실제로 끝나기 전에 성공 또는 실패로 처리될 수 있다.
- 심각도: **High**
- S4 전 조치: 발화별 세대 번호/리스너 토큰을 사용해 현재 발화에 해당하는 콜백만 완료시켜야 한다.

### 7. 높음: TTS 동시 발화가 코드 수준에서 차단되지 않음

- 파일: `PleosSpeechSink.kt:67-80`
- 시나리오: 코어 엔진의 재시도, 외부 `stop()`, 재초기화 또는 향후 호출 경로에서 `speak()`가 동시에 실행된다.
- 결과: `current`가 덮어써지고 두 `speak()`가 같은 TTS 인스턴스를 공유한다. 한 발화의 종료 콜백이 다른 발화의 대기 상태를 깨뜨릴 수 있다.
- 심각도: **High**
- S4 전 조치: `speak/stop/release`를 하나의 Mutex 또는 단일 dispatcher에서 직렬화해야 한다.

### 8. 중간: TTS SDK 예외가 SpeechSink 계약의 실패값으로 변환되지 않음

- 파일: `PleosSpeechSink.kt:67-80`
- 시나리오: `ready()` 이후 `t.speak()`가 `SecurityException`, SDK 미설치 예외, 서비스 단절 예외를 던진다.
- 결과: `SpeechSink` 계약은 실패 시 `false`를 반환해 코어 스케줄러가 재시도·폐기하도록 되어 있지만, 현재는 `CancellationException` 외 예외가 그대로 전파된다. 엔진의 발화 코루틴이 취소될 수 있다.
- 심각도: **Medium**
- S4 전 조치: SDK 호출 예외를 로깅 후 `false`로 변환하고, 취소 예외만 재전파해야 한다.

### 9. 높음: TTS release가 진행 중 발화를 정리하지 않음

- 파일: `PleosSpeechSink.kt:83-90`, `PikoApp.kt:35`
- 시나리오: 세션 종료나 프로세스 종료 직전에 `release()`가 호출되거나, 향후 호출자가 release를 추가한다.
- 결과: 진행 중 `speak()`의 deferred를 완료하지 않고 TTS 객체만 release한다. 대기 코루틴은 최대 8초까지 남을 수 있고, 늦은 SDK 콜백이 release된 객체 상태와 경합할 수 있다.
- 심각도: **Medium**
- S4 전 조치: `stop → 현재 deferred 완료 → release → null` 순서를 보장해야 한다.

### 10. 중간: 바인더 콜백에서 일관되지 않은 스냅샷을 생성할 수 있음

- 파일: `PleosVehicleGateway.kt:55-60, 162-166`
- 시나리오: 속도·기어·UX 콜백이 서로 다른 바인더 스레드에서 동시에 실행된다.
- 결과: 각 필드는 `volatile`이지만 세 필드를 하나의 원자적 상태로 읽는 것은 아니다. 속도는 새 값, 기어는 이전 값, UX는 다른 시점의 값인 혼합 스냅샷이 방출될 수 있다.
- 심각도: **Medium**
- S4 전 조치: 단일 락, 단일 dispatcher, 또는 immutable snapshot을 원자적으로 교체하는 방식으로 상태 갱신을 직렬화해야 한다.

### 11. 높음: 실패·미지원 상태에서 안전 우선 부팅이 무기한 지속될 수 있음

- 파일: `PleosVehicleGateway.kt:45-49, 120-125`, `MainActivity.kt:63-64`, `LiveScreen.kt:49-51`
- 시나리오: UX manager가 없거나, 기어/속도 콜백 등록이 실패하거나, 권한이 거부된다.
- 결과: 코어는 `BOOTING`을 유지하도록 설계되어 있으나 UI에는 `F3Driving`이 표시된다. 사용자는 실제 주행 여부를 알 수 없고, 권한 거부 상황에 대한 “조용한 모드”나 정차 시 설정 유도도 구현되어 있지 않다.
- 심각도: **High**
- S4 전 조치: `BOOTING/권한 거부/채널 미지원`을 구분하고, 확인 전에는 텍스트·버튼 없는 보수 화면을 유지하되 실패 상태를 내부적으로 명시해야 한다.

### 12. 중간: R단 무음 정책이 TTS sink 단독으로는 완전히 보장되지 않음

- 파일: `PleosSpeechSink.kt:83-86`, `PikoSession.kt:68-74`
- 시나리오: 기어 R 이벤트와 발화 코루틴이 서로 다른 실행 흐름에서 거의 동시에 처리된다.
- 결과: R단 전환 시 `engine.onModeChanged()`가 `stop()`을 호출하는 구조가 코어에 의존하지만, `PleosSpeechSink` 자체에는 현재 발화와 stop 사이의 직렬화가 없다. 경합 시 `speak()`가 `t.speak()`를 호출한 뒤 늦게 stop되거나, 새 발화가 stop 이후 시작될 수 있다.
- 심각도: **High**
- S4 전 조치: 모드 변경·발화 시작·stop을 동일한 직렬화 경로로 묶고, R 상태에서는 `speak()` 진입 자체를 거부해야 한다.

### 13. 중간: EvidenceLog의 speech 로그가 개인정보·운전자 노출면을 넓힘

- 파일: `EvidenceLog.kt:29-32`
- 시나리오: 실제 발화 문장과 시각을 `logcat`으로 수집하거나 외부 증거 패키지에 포함한다.
- 결과: 로그에 발화 전문이 남는다. 현재 문장이 고정 카피라도 향후 운전자별 데이터가 들어가면 안전 로그와 사용자 콘텐츠가 혼합된다.
- 심각도: **Medium**
- S4 전 조치: 증거 로그에는 `lineId/category/outcome`만 남기고 텍스트 전문은 제거하는 편이 안전하다.

### 14. 중간: core의 `events`는 증거 수집에 대해 손실 가능성이 명시적으로 존재함

- 파일: `core/src/main/kotlin/com/pikodrive/core/pet/PikoSession.kt:40-42, 68-74`
- 근거: `0e7effe`에서 `MutableSharedFlow(extraBufferCapacity = 64)`와 `tryEmit()`가 추가됨.
- 시나리오: EvidenceLog 수집이 일시적으로 느리거나 다른 collector가 추가된다.
- 결과: 이벤트가 버퍼를 초과하면 증거 로그만 조용히 누락된다. 엔진 동작에는 영향이 없지만 S3 증거 완전성은 보장되지 않는다.
- 심각도: **Medium**
- S4 전 조치: 증거가 필수라면 별도 durable log 또는 최소한 drop counter를 두고, 증거용 이벤트와 엔진용 이벤트의 요구사항을 분리해야 한다.

## S4 전에 반드시 고칠 순서

1. 권한 거부 후 무조건 차량 세션을 시작하는 문제
2. `callbackFlow` 채널별 예외 격리
3. TTS 발화 세대·동시성·stop/release 직렬화
4. 차량 `Car.disconnect()`와 세션 수명 관리
5. `startSession()` 중복 생성 방지
6. R단 무음 및 BOOTING/실패 상태 보장
7. EvidenceLog의 전문 로그와 이벤트 손실 처리

S3 게이트 판정: **NO-GO — 정상 E2E는 통과했지만 권한 거부·SDK 실패·TTS/차량 재초기화 경로에서 안전 정책과 리소스 수명이 보장되지 않아 S4 진행 전 수정이 필요함.**