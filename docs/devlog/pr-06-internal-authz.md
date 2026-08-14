# PR #6 — /internal 관리 표면 HMAC 인가

이 문서는 게이트웨이의 `/internal` 관리 표면(배포 시 라우트를 전환하는 내부 API)에 인가를 세운 과정을 다룬다. 이 표면은 배포 agent만 불러야 하는데 그때까지 인증이 없었다 — LAN 경계(방화벽·NAT)에만 의존했다. 이 작업의 특이한 점은 리뷰가 "코드는 맞아 보이는데 실제로는 열려 있는" fail-open을 잡아냈다는 것, 그리고 실운영 중인 게이트웨이에 인증을 **무중단으로** 켜야 했다는 것이다.

## 문제와 원인 — fencing은 순서지 인가가 아니다

`/internal`에는 이미 fencing token 검증이 있었다. 하지만 fencing은 "낡은 실행자의 순서"를 막는 장치이지 "누가 부르는가"를 판정하는 인가가 아니다. LAN 안의 어떤 프로세스든 라우트 상태를 조회해 마지막 token을 읽고, 거기에 +1을 붙여 전환을 호출할 수 있었다. 위협 모델은 제한적이지만(LAN 내부) 인가의 부재는 구조적이다.

## 검토한 선택지와 채택

세 가지를 놓고 봤다. **HMAC 공유 비밀**(agent와 gateway가 공유 키로 요청 서명), **관리 포트 분리**(/internal을 내부 인터페이스에만 바인딩), 그리고 **둘 다**. HMAC을 택했다 — 배포 파이프라인이 이미 게이트 1(CI↔agent)에서 HMAC 계약을 쓰고 있어 패턴이 일관되고, 무엇보다 "누가 부르는가"를 실제로 판정한다. 포트 분리는 LAN 의존을 또 다른 네트워크 의존으로 바꿀 뿐이고, 포트 분리만으로는 인가가 서지 않는다.

canonical 서명 형식은 배포 게이트 1과 같은 계열이되 관리 표면에 맞춰 4필드(method·path·body digest·timestamp)로 뒀다. 키는 배포 게이트 1의 `AGENT_HMAC_KEY`와 **별도**다 — 관리 표면과 배포 요청은 다른 신뢰 경계이기 때문이다.

```kotlin
// InternalAuthWebFilter.kt (발췌) — 인가가 fencing보다 먼저
// 최우선 WebFilter가 /internal/**를 canonical-v1 HMAC로 인가한다. 무서명은 body를 읽기 전
// 즉시 판정하고(인증 전 body를 읽지 않는다), 서명 요청만 크기 제한 후 캐시해 컨트롤러에
// 재공급한다. 오류 어느 경로도 chain으로 새지 않는다(fail-closed).
```

## 설계가 두 번의 선검증으로 단단해졌다

이 작업은 코드 전에 codex 선검증이 5건 BLOCK을 냈고, 그중 하나가 사용자 결정("요청만 서명")을 뒤집었다 — 평문 HTTP에서 응답에 서버가 만들었다는 증거가 없으면 위조된다는 지적이었다. 그래서 응답도 HMAC으로 서명하도록 바꿨다. Opus 확인 라운드가 5건을 더 냈는데, 그중 `auth.Sign`이 6필드 canonical을 끌고 온다는 지적(관리 표면은 4필드라 그대로 부르면 전건 불일치)이 interop 파손을 막았다.

## 리뷰가 잡은 실 fail-open

구현 후 듀얼 리뷰에서 codex가 **실제 fail-open**을 잡았다 — 모드 값을 `trim().lowercase()`로 정규화해서, 계약상 기동을 거부해야 할 `AUDIT`·` audit `(대소문자·공백 변형)이 audit로 수락됐다. `GATEWAY_INTERNAL_AUTH_MODE=' AUDIT '`로 띄우면 무서명 GET이 200으로 통과하는 걸 재현했다. 코드는 맞아 보였지만 정규화가 안전장치를 뚫은 것이다. 수정은 원문 그대로 정확 비교하는 것이었다.

```kotlin
// InternalAuthWebFilter.kt (발췌) — 정확 비교
// 정확 비교(원문 그대로·대소문자 구분·주변 공백 불허). trim·lowercase 하면 ` AUDIT `·
// `AUDIT`이 audit로 접혀 무서명이 열린다(codex 재현 — 실 fail-open).
fun parseMode(raw: String): InternalAuthMode = when (raw) {
    "audit" -> InternalAuthMode.AUDIT
    ...
```

Opus는 fail-open이 없음을 독립 확인하면서 Go(agent)와 Kotlin(gateway)의 path 인코딩 비대칭을 잡았다. 두 리뷰가 상보적으로 작동해, codex는 실 fail-open과 테스트 그린위장을, Opus는 계약 비대칭을 각각 봤다.

## 무중단 이관 — audit에서 enforce로

인증을 붙이는 쪽(agent)과 검사하는 쪽(gateway)은 다른 시점에 배포된다. gateway를 바로 강제(enforce)로 켜면 아직 서명 안 붙이는 agent 호출이 401나서 배포가 끊긴다. 그래서 두 단계로 켰다 — 먼저 agent가 서명을 붙이도록 배포하고, gateway를 **audit**(서명 있으면 검증·없으면 통과+경고)로 띄워 실제 배포에서 agent의 호출이 "valid-signature 양성"으로 찍히는지 관측했다. 확인되면 **enforce**로 올려 무서명을 차단했다. 이 개념 차이는 study의 [audit-enforce-rollout](../../../docs/study/tech/security/audit-enforce-rollout.md)에 정리했다.

실서버 이관에서 미묘한 함정 하나를 실측했다 — pass-through env는 agent 프로세스가 기동 시점의 `.env`를 메모리에 로드하므로, host `.env`만 enforce로 바꾸면 agent가 여전히 구값(audit)을 compose에 주입한다. 1차 enforce가 audit로 뜬 원인이었고, agent 재기동으로 해결했다. runbook에 남긴 절차 지식이다.

## 다음으로의 연결

인가가 서면서 fencing token의 rollback 재전송 창(전환 실패 후 30초 안에 원본 서명 재전송이 죽은 slot으로 재전환)이 별도 이슈(#11)로 분리됐다 — 인가와 fencing 재설계를 섞지 않는 판단이다. 실운영은 enforce까지 완료됐다.
