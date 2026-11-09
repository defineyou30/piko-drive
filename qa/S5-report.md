# S5 안정화·증거 리포트 (2026-10-24)

## 엣지 플로우 실측
| 시나리오 | 결과 | 근거 |
|---|---|---|
| 주행 중 앱 강제 종료 → 재실행 | BOOTING → 약 200ms 후 DRIVING. 터치 UI 노출 0: 0~4초 녹화 4fps 16프레임 전부 주행 화면, 3~20초 UI 트리 검사 8회(약 2.4초 간격) 전부 ok | `qa/S5/restart/boot-frames.png`, `boot-first5s.mp4`, `checks.log`, `evidence.log` |
| 위 상황에서 정차 | 재시작 시점부터의 트립 리포트(F6) 표시 | `qa/S5/restart/checks.log` (after_park) |
| 목소리 끄기 | 발화 없음, 기본 표정 QUIET | S4 흐름 (`qa/S4/06-F8-voiceoff.png`) |
| 제출 APK(`dist/PikoDrive_release.apk`) 클린 설치(`adb uninstall` 후 `adb install`) | 90초 데모 자동 판정 PASS, 설치 APK SHA-256 = 제출 APK SHA-256 | `qa/S5/release-demo90/verdict.log`, `qa/S5/release-apk.txt` |
| 코어 단위 테스트 | 53 통과 / 0 실패 | `qa/S5/core-tests.txt` |
| 권한 거부 / 오디오 포커스 거부 | 에뮬레이터 재현 불가 → 코드 경로·단위 테스트 | `qa/EVIDENCE.md` 6장 |

## 제출 빌드
- `./gradlew :app:assembleRelease` → 서명된 release APK. 키는 `local.properties`(git 제외)에만 있다.
  키가 없는 환경에서는 debug 키로 서명된다 → **기능 빌드 재현용**이며 제출 APK와 같은 인증서는 아니다.
- 제출 APK 고정: `qa/S5/release-apk.txt` (파일 SHA-256, 빌드 커밋, apksigner 전체 출력)
- 서명 인증서 SHA-256: `49ae328b53ebfc05ca4cf3a9790636a0ab151c8ef1390d273787ca5a2c8870d5`
- 크기 약 47MB (서체 3종 4.7MB 포함, 난독화 미적용 — 심사 시 소스 대조 용이성 우선)

## 증거 패키지
`qa/EVIDENCE.md` — 안전 수치, 판정 정확도, 반응 속도, 재현성, 지원 매트릭스

## Codex 리뷰 반영 (`reviews/S5-codex.md`, 판정: 동결 불가 → 보강 후 동결)
| 지적 | 조치 |
|---|---|
| release APK와 데모 로그의 동일성 미입증, 지문 축약 | `dist/`에 제출 APK 고정, 전체 해시·apksigner 출력 저장, 데모 스크립트가 설치된 APK를 받아 해시·버전을 `verdict.log`에 기록 → 일치 확인 |
| PASS가 로그 해석에 의존 | `verdict.log`에 판정 항목·결과 저장 |
| "0.3초 간격 8회" 원시 근거 없음 | 실제 간격(약 2.4초)으로 정정, 시각별 결과 `checks.log` + 0~4초 화면녹화 프레임 시트 추가 |
| 52/53 불일치 | 실행 로그 저장, 차이(S3 테스트 1개 추가) 명시 |
| 300ms·수신 간격 수치의 측정 범위 | "구현 상수", "S0 측정"으로 표기 |
| "즉시 복귀", "심사 측 재현 가능" 표현 | "약 200ms", "기능 빌드 재현용(인증서 다름)"으로 정정 |
| 실측/단위 테스트/코드 경로 혼재 | 매트릭스를 검증 수준별 열로 분리 |

## S5 게이트: **통과 → 기능 동결 `v0.1-submit`** (2026-10-24, Claude)
