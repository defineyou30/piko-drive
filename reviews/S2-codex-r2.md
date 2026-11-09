1. **부분** — `DriveModeResolver.kt:41-47`에서 UX 제한 미확인 시 부팅 중에는 `BOOTING`이지만, 이미 `DRIVING`이면 `DRIVING`으로 유지합니다. `PetEngine.kt:221`의 `allowsReaction()`은 `DRIVING`을 허용하므로 UX 제한 미확인 중 발화 가능성이 남습니다.

2. **해결** — `PetEngine.kt:70-85, 112-120`에서 `modeGeneration`을 캡처하고 모드 변경 시 지연 작업을 취소합니다. D→R→D 전환 테스트도 추가됐습니다.

3. **부분** — `PetEngine.kt:86-99`, `Speech.kt:17-20`에서 모드 전환 시 `speechJob.cancel()`과 `speechSink.stop()`을 호출합니다. 다만 `SpeechSink.stop()` 기본 구현이 no-op이고, `speak()`의 취소 협조가 구현체 계약에만 의존합니다(`Speech.kt:12-20`).

4. **해결** — `DrivingEventDetector.kt:73-83`에서 새 트립 시작 시 `lastBrakeMs`와 `lastAccelMs`를 초기화합니다.

5. **해결** — `DrivingEventDetector.kt:49-64`에서 직전 모드가 주행이면 현재 샘플까지의 마지막 D 구간을 적립합니다. R 구간은 적립하지 않습니다.

6. **해결** — `PikoSession.kt:37-48`에서 실행 중인 Job을 재사용하고, `stop()`으로 수집을 취소합니다.

7. **해결** — `PetEngine.kt:187-207`, `SpeechBudget.kt:9-25`에서 카테고리와 무관한 최소 간격·시간당 횟수 예산을 적용합니다.

8. **해결** — `PetEngine.kt:79`에서 `onModeChanged()`가 `internal`로 제한되어 외부 모듈의 임의 주입을 차단합니다.

추가 높음 심각도 버그: **UX Restriction 값이 주행 중 `null`로 사라지면 `DriveModeResolver.kt:41-47`이 안전 모드로 전환하지 않고 기존 `DRIVING` 정책을 유지해 발화가 허용될 수 있습니다.**

**S2 게이트: 미통과** (1번·3번 부분 해결 및 추가 높음 심각도 버그).