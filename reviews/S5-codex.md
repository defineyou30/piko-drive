## 1. 근거보다 과장된 수치·주장

- **높음 — “release, 서명” 빌드라는 전제가 입증되지 않음.**  
  `qa/EVIDENCE.md`는 전체 측정이 `v0.1-submit (release, 서명)`이라고 단정하지만, `qa/S5/release-demo90/`에는 APK 자체, APK 해시, 설치 명령, 서명 검증 결과가 없다. `qa/S5-report.md`의 인증서 SHA-256도 `49ae328b…a2c8870d5`로 중간값이 생략되어 독립 검증이 불가능하다.  
  근거: `qa/EVIDENCE.md:5`, `qa/S5-report.md:13-15`, `qa/S5/release-demo90/evidence.log`

- **높음 — `app-release.apk`가 실제 S5 데모에 사용된 빌드인지 연결되지 않음.**  
  `qa/S5/release-demo90/evidence.log`에는 앱 프로세스와 이벤트만 있고 version/build ID, APK 파일명, APK SHA-256, 설치 시각이 없다. 따라서 해당 로그가 현재 `app/build/outputs/apk/release/app-release.apk`에서 나온 것인지 확인할 수 없다.  
  근거: `qa/S5/release-demo90/evidence.log`, `qa/S5-report.md:9-15`

- **중간 — “90초 데모 PASS”는 자동 판정 결과가 아니라 로그 해석이다.**  
  release 로그에는 `PASS`나 판정 스크립트 출력이 없다. host 로그는 약 93초 동안 차량 입력을 보낸 사실만 보여주며, 결과 로그에는 이벤트와 최종 trip만 있다.  
  근거: `qa/S5/release-demo90/host.log`, `qa/S5/release-demo90/evidence.log`, `tools/s4_demo90.sh`

- **중간 — “5회 연속 PASS”의 범위가 불명확하다.**  
  `qa/EVIDENCE.md`는 debug run1~4와 release 1회를 합산하지만, 5회가 동일한 판정 기준과 동일한 자동 검증으로 PASS했음을 보여주는 통합 판정표가 없다. S4 보고서는 run1~4만 별도로 설명한다.  
  근거: `qa/EVIDENCE.md:49`, `qa/S4-report.md:22-28,58-61`, `qa/S4/demo90-run1~4/*`

- **중간 — “주행 중·재시작 직후 0.3초 간격 8회 검사”의 8회 측정값이 없다.**  
  `qa/S5/restart-driving.log`에는 `BOOTING`과 `DRIVING` 시각이 두 번 기록될 뿐, 0.3초 간격 8회의 검사 시각·결과가 기록되어 있지 않다. `restart-driving.png` 한 장으로는 해당 시계열 주장을 입증할 수 없다.  
  근거: `qa/EVIDENCE.md:19`, `qa/S5/restart-driving.log`, `qa/S5/restart-driving.png`

- **중간 — 발화 중 후진 무음 `≤360ms`는 합산 근거가 불완전하다.**  
  `qa/S3/reverse/evidence.log`에는 앱의 `mode REVERSING`과 TTS stop 로그는 있으나, `콘솔→앱 <150ms` 측정값과 `모드 전환→무음 205ms` 산출 과정이 한 파일에 재현 가능하게 묶여 있지 않다. 실제 로그에서도 reverse 시점과 TTS stop 시점의 기준이 명확하지 않다.  
  근거: `qa/EVIDENCE.md:18`, `qa/S3/reverse/evidence.log`, `qa/S3/reverse/host.log`, `qa/S0-probe.md:40`

- **중간 — “후진 중 발화 0, 3회 데모 자동 판정”의 근거가 약하다.**  
  `qa/EVIDENCE.md`의 근거는 `tools/s4_demo90.sh`뿐이며, S5 대상 근거 파일에 3회 자동 판정 결과가 없다. 각 데모 로그에서 reverse 이후 `SPOKEN`이 없다는 사실과 자동 판정 성공은 구분되어야 한다.  
  근거: `qa/EVIDENCE.md:25`, `tools/s4_demo90.sh`, `qa/S4/demo90-run1~4/evidence.log`

- **중간 — “화면 전환 300ms 크로스페이드”는 S5 증거가 없다.**  
  해당 수치는 구현 상수와 설명에 가깝고, S5 근거 파일에는 프레임 타임스탬프나 영상 분석 결과가 없다. 과거 S1 자료도 300ms 검증을 후속 항목으로 남겼다.  
  근거: `qa/EVIDENCE.md:44`, `qa/S4-report.md:10`, `qa/S1-report.md:75`, `FRAMEWORK.md:76`

- **낮음 — “코어 단위 테스트 53개 전부 통과”는 실행 증거가 없다.**  
  현재 테스트 선언 수는 53개로 보이지만, 실행 결과 파일은 없다. 오히려 `qa/S2-core-report.md`에는 이전 실행이 52개라고 기록되어 있어 기준 시점이 충돌한다.  
  근거: `qa/EVIDENCE.md:50`, `qa/S2-core-report.md:4,157`, `core/src/test/**`

- **낮음 — 차량 데이터 수신 간격 수치가 S5 측정이 아니다.**  
  `131ms/p90 200ms`는 S0 probe의 30초 측정값이며, S5 release 빌드에서 동일하게 측정했다는 증거가 없다. EVIDENCE 문서의 “모든 수치는 Pleos 에뮬레이터 v2.1.0 실측”이라는 문구와 S5 release 증거의 범위가 섞여 있다.  
  근거: `qa/EVIDENCE.md:3,43`, `qa/S0-probe.md:11`

## 2. 심사위원이 의심할 표현

- **“즉시 복귀”**: 로그상 `BOOTING`에서 `DRIVING`까지 약 205ms이므로 “즉시”는 정량 표현과 충돌한다. `약 205ms 내 주행 모드 진입`처럼 써야 한다.  
  근거: `qa/S5-report.md:6`, `qa/S5/restart-driving.log`

- **“심사 측 재현 가능”**: 키가 없으면 debug 서명으로 fallback하는 구조는 기능 빌드 재현성을 높일 수 있지만, 제출 APK와 동일한 인증서 재현을 의미하지 않는다. 심사자가 다른 키로 빌드한 APK를 동일 앱 업데이트 대상으로 사용할 수도 없다.  
  근거: `qa/S5-report.md:13`, `app/build.gradle.kts:29-44`

- **“release 서명 APK 클린 설치”**: 클린 설치 수행 명령과 설치 대상 APK 식별자가 없다. 디렉터리 존재만으로는 클린 설치를 증명하지 못한다.  
  근거: `qa/S5-report.md:9`, `qa/S5/release-demo90/`

- **“재시작 시점부터의 트립 리포트”**: 로그에는 `trip 24s ...`가 있으나, 기존 트립 데이터가 폐기되었는지와 새 세션에서 생성된 리포트인지 비교 근거가 없다.  
  근거: `qa/S5-report.md:6`, `qa/S5/restart-driving.log`

- **“발화 없음, 기본 표정 QUIET”**: 음성 끄기 근거가 PNG 한 장뿐이다. 무발화는 오디오 로그나 상태 로그 없이는 시각 자료로 검증할 수 없다.  
  근거: `qa/S5-report.md:8`, `qa/S4/06-F8-voiceoff.png`

## 3. 기능 동결 전에 반드시 고칠 결함

1. **제출 APK의 실제 서명과 증거를 고정해야 한다.**  
   APK를 하나의 명시적 산출물로 복사·해시하고, `apksigner verify --verbose --print-certs` 결과를 저장해야 한다. 인증서 SHA-256은 축약 없이 기록해야 한다. 현재 `build.gradle.kts`는 키가 없으면 debug 서명으로 조용히 fallback하므로 “제출용 release 서명”이라는 보장이 없다.  
   근거: `app/build.gradle.kts:29-44`, `qa/S5-report.md:13-15`

2. **S5 데모와 APK의 동일성 증거를 추가해야 한다.**  
   설치한 APK의 SHA-256, versionName/versionCode, 설치 명령, 실행 시각을 `qa/S5/release-demo90/`에 남겨야 한다. 그렇지 않으면 release 데모가 어떤 빌드인지 확인할 수 없다.  
   근거: `qa/S5/release-demo90/*`, `qa/S5-report.md:9`

3. **자동 판정 로그를 보존해야 한다.**  
   90초 데모의 PASS 기준, 검사 항목, 실제 판정 결과를 stdout 또는 별도 `verdict.log`로 남겨야 한다. 현재는 사람이 이벤트 로그를 읽고 PASS를 추정해야 한다.  
   근거: `tools/s4_demo90.sh`, `qa/S5/release-demo90/evidence.log`

4. **재시작 안전성 검사를 원시 시계열로 남겨야 한다.**  
   8회 UI 검사 각각의 타임스탬프와 결과를 기록해야 한다. 현재 로그만으로는 “8회 모두 0”을 검증할 수 없다.  
   근거: `qa/S5/restart-driving.log`

5. **S5 기준 테스트 수와 실행 결과를 일치시켜야 한다.**  
   `52개`와 `53개` 중 실제 현재 실행 결과를 확정하고, 실행 명령 및 전체 summary를 S5 근거 파일에 보존해야 한다.  
   근거: `qa/S2-core-report.md:4`, `qa/EVIDENCE.md:50`

6. **권한 거부·오디오 포커스 거부를 기능 동결 전에 최소한 독립 테스트로 명확히 구분해야 한다.**  
   EVIDENCE는 “단위 테스트/코드 경로”를 실연동 확인처럼 같은 표에 배치한다. 심사 제출 문서에서는 실차·에뮬레이터 실측과 코드 커버리지를 별도 열로 분리해야 한다.  
   근거: `qa/EVIDENCE.md:55-56`, `qa/S5-report.md:11`

## 4. 서명·빌드 재현성

- `app/build.gradle.kts`는 `piko.storeFile`이 없으면 release variant를 debug 키로 서명한다. 따라서 동일 소스와 동일 명령이라도 `local.properties` 존재 여부에 따라 인증서가 달라진다.
- `local.properties`는 git 제외 대상이므로 심사자나 CI가 동일 서명 APK를 재현할 수 없다. 현재 문구의 “심사 측 재현 가능”은 기능 빌드 재현에 한정해야 한다.
- 서명 키 파일의 경로가 `local.properties`에만 있고, 키스토어 버전·인증서 공개 지문·빌드 입력값이 증거 패키지에 없다.
- `qa/S5-report.md`의 인증서 지문은 축약되어 있어 APK와 대조할 수 없다.
- 현재 환경에서는 Gradle 검증도 `~/.gradle/...zip.lck` 쓰기 권한 오류로 실행되지 않았다. 따라서 S5 리뷰 시점에 `assembleRelease` 성공을 재확인한 실행 산출물이 없다.
- APK에 대한 `jarsigner` 검증은 Android APK 서명 검증 방법이 아니며, 이 검증 결과로 서명 여부를 판단할 수 없다. Android SDK의 `apksigner` 검증 결과가 필요하다.

S5 게이트(기능 동결 가능 여부): **불가 — 서명 APK 동일성·재현성, 자동 판정 로그, 재시작 8회 검사 원시 근거를 보강한 뒤 기능 동결해야 함.**