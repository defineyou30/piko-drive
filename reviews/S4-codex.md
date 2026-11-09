- **치명적 — 차량 데이터 출처가 금지된 브랜드명으로 노출됨.** `FRAMEWORK.md`는 Pleos 명칭·로고 사용을 금지하지만 F9에 `Pleos`를 표시합니다. 출품 화면에서는 차량 연동을 표현하되 브랜드명은 중립 명칭으로 바꿔야 합니다.  
  `app/src/main/java/com/pikodrive/ui/screens/Screens.kt:370,382`

- **높음 — F9 “목소리 테스트”가 실제 음성 테스트가 아님.** 버튼이 `petByHand()`를 호출해 AFFECTION 반응을 발생시킬 뿐, 음성 테스트 전용 발화가 아닙니다. 음성 off 상태에서도 “테스트” 버튼이 눌리지만 무엇을 검증했는지 알 수 없습니다.  
  `app/src/main/java/com/pikodrive/ui/live/LiveScreen.kt:121`  
  `app/src/main/java/com/pikodrive/ui/screens/Screens.kt:377`

- **높음 — 첫 실행 플로우가 주행 상태와 경합할 수 있음.** F0 완료 플래그는 버튼 클릭 후 비동기 DataStore 저장에 의존합니다. 저장 전에 차량 상태가 바뀌거나 프로세스가 재생성되면 다시 F0으로 진입할 수 있고, 사용자가 이미 약속 화면을 통과했는지 보장되지 않습니다.  
  `app/src/main/java/com/pikodrive/ui/live/LiveScreen.kt:94-95`  
  `app/src/main/java/com/pikodrive/PikoApp.kt:97-99`

- **높음 — 세션 재시작 중 화면 상태가 보존되지 않는 경합이 있음.** F9에서 데모 설정을 바꾸면 `closeSession()`이 `_session`을 null로 만들고 새 세션을 별도 코루틴으로 시작합니다. 그 사이 `MainActivity`는 안전 화면 F3을 표시하며, 재시작 완료 전 사용자 입력을 받지 않습니다. 재시작 중 F3이라는 상태 설명이나 로딩 차단이 없어 PT 조작 시 막다른 화면처럼 보일 수 있습니다.  
  `app/src/main/java/com/pikodrive/PikoApp.kt:89-95,112-127`  
  `app/src/main/java/com/pikodrive/MainActivity.kt:64-68`

- **높음 — 주행 중 초기 안전 화면이 실제 상태와 무관하게 DRIVING으로 그려짐.** 세션 생성 전에는 항상 `F3Driving(..., DriveMode.DRIVING)`을 렌더링합니다. 부팅 중 후진 상태가 먼저 들어오거나 UX 제한 확인 전이라도 DRIVING 표정/밝기 규칙이 적용될 수 있습니다. 기준 문서의 “확인 전 F3 형태”는 지키지만, R단 정지 화면까지는 보장하지 않습니다.  
  `app/src/main/java/com/pikodrive/MainActivity.kt:65-68`

- **중간 — 리포트 시각 변환이 서로 다른 시계 기준에 의존함.** 엔진 이벤트 시각, 저장소 변환, UI 변환이 모두 단조 시계라는 전제를 공유하지만 Pleos 게이트웨이·차량 이벤트 시각이 실제로 동일한 기준인지 코드상 강제하지 않습니다. 기준 문서와 S2 리포트도 이 위험을 보류 항목으로 기록하고 있습니다. 시계 기준이 어긋나면 리포트 시간대와 놀란 순간 타임라인이 역전될 수 있습니다.  
  `app/src/main/java/com/pikodrive/storage/DataStorePetRepository.kt:61-63,66-79`  
  `app/src/main/java/com/pikodrive/ui/live/LiveScreen.kt:180,196-203`  
  `qa/S2-core-report.md:110`

- **중간 — 앱 프로세스 강제 종료 시 진행 중 세션의 저장 경로가 없음.** 펫 상태 저장은 Flow 변화 또는 `closeSession()`에 의존하고, 리포트는 주차 이벤트에서만 생성됩니다. Android 프로세스가 주행 중 종료되면 해당 트립은 리포트로 복구되지 않습니다. S4 리포트의 “앱 재시작/크래시” 검증은 정상 세션 재시작과 크래시 로그 0만 확인하며, 주행 중 프로세스 종료 복구는 검증하지 않았습니다.  
  `core/src/main/kotlin/com/pikodrive/core/pet/PikoSession.kt:58-75`  
  `app/src/main/java/com/pikodrive/PikoApp.kt:117-127`  
  `qa/S4-report.md:19-27`

- **중간 — 차량 특화성이 화면에서 약하고, F3는 빈 화면 비중이 과도함.** 첨부 화면 기준 주행 화면은 어두운 배경과 작은 단일 픽셀만 남아 차량 상태·피코의 반응·주행 맥락이 거의 전달되지 않습니다. 정보 제한은 맞지만, 차량 HMI 전용 앱이라기보다 일반적인 다크 모드 로딩 화면처럼 보일 위험이 있습니다.  
  `app/src/main/java/com/pikodrive/ui/screens/Screens.kt:159-176`  
  `app/src/main/java/com/pikodrive/ui/live/LiveScreen.kt:78-93`

- **중간 — F1의 숨은 데모 진입은 발견 가능성과 심사 설명성이 낮음.** 단계 라벨 5회 탭은 기능상 동작하지만 일반 사용자가 알아낼 수 없고, 심사 중 실수로 진입할 가능성도 있습니다. 기준에는 숨김 진입이 정의되어 있으나 실제 PT 흐름에서는 별도 안내나 명확한 데모 진입 증거가 부족합니다.  
  `app/src/main/java/com/pikodrive/ui/live/LiveScreen.kt:131-138`  
  `app/src/main/java/com/pikodrive/ui/screens/Screens.kt:129-134`

- **중간 — QA가 확인한 것은 성공 경로 중심이며, 핵심 실패 경로 증거가 없음.** `qa/S4-report.md`에는 첫 실행·정상 주행·리포트 재열람·90초 데모만 기록되어 있습니다. 권한 거부, 주행 중 재시작, 차량 데이터 미지원, 시계 변경, F9 재시작 중 입력, 음성 테스트의 실제 발화 여부는 S4 게이트 증거에 없습니다.  
  `qa/S4-report.md:17-31`

시각 품질 측면에서는 색상·픽셀 규칙은 일관되지만, 화면 구성이 좌우 2열 템플릿과 큰 빈 공간에 의존해 차량 특화 인터랙션보다 일반적인 콘셉트 목업에 가깝습니다. 심사 관점에서 가장 약한 부분은 “차량 상태를 읽고 안전하게 반응하는 동승자”가 화면보다 로그와 데모 영상에 의존한다는 점입니다.

S4 게이트 판정: **불통과**.