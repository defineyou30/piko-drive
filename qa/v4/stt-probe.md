# Pleos SpeechToText 실측 (2026-10-26, 에뮬레이터 v2.1.0)

- SDK: `ai.pleos.playground:SpeechToText:3.3.1` (Pleos Nexus, TTS 3.3.1과 같은 버전대). 권한 `pleos.car.permission.STT_SERVICE`, `RECORD_AUDIO`.
- API: `SpeechToText(context, Mode.ON_DEVICE|HYBRID|SERVER)` → `initialize` → `addListener(ResultListener)` → `request()` (마이크) 또는 `sendAudio(pcm)`+`completeAudioSend()` (오디오 직접 주입). 부분/최종 결과 `onUpdated(text, isFinal)`. 단어 가중치 `LMBoostConfig` 있음(미시험).
- 에뮬레이터엔 실제 마이크 입력이 없어, macOS `say -v Yuna`로 만든 음성을 16kHz·16bit·mono PCM으로 `sendAudio` 주입 (probe `--es stt file`).

| 모드 | 초기화 | 결과 |
|---|---|---|
| ON_DEVICE | OK | 아래 표 |
| HYBRID | OK | 결과 없음 (서버 경로로 보임, 에뮬레이터 네트워크·인증 미확인) |
| SERVER | OK | ERROR UNKNOWN |

| 입력 문장 (합성 음성) | ON_DEVICE 최종 결과 |
|---|---|
| 알림장 받아 오기 | 알림장 할 때 후기 |
| 수요일 오후 세시 태권도 | 수요일 오후 3시 태국은 또 |
| 매주 월요일이랑 수요일 오후 세시 사십분에 지우 태권도, 준비물은 도복이랑 띠 | 한국이랑 (실패) |

판단: 짧은 말은 요일·시각·숫자까지 잡는다("세시"→"3시"로 정규화). 고유어(태권도·별칭)는 틀림 → `LMBoostConfig`에 일정·준비물 칩 단어와 아이 별칭을 넣어 볼 것. 긴 한 문장은 실패 → "한 번에 한 칸씩 말하기" 또는 짧은 문장 + 칩 확인 방식이 맞다.
한계: 합성 음성 주입이라 실제 차량 마이크·사람 목소리와 다를 수 있다.
