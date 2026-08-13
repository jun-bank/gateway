# PR #2 — 동적 라우트 + 블루-그린 전환 내부 API

이 문서는 gateway가 core 블루-그린 전환의 실행 지점이 된 과정을 다룬다. 무중단 배포를 블루-그린으로 한다는 결정(ADR-024)은 있었지만, 전환이 "무엇을 조작하는가"는 오래 비어 있던 칸이다 — 엣지 nginx 설정 교체인가, compose 포트 스왑인가. ADR-031이 그 칸을 SCG(Spring Cloud Gateway) 라우트 전환으로 채웠고, 이 PR이 그 결정을 코드로 세웠다. 이 PR의 리뷰는 이번 여정에서 설계를 가장 크게 바꾼 리뷰이기도 하다.

## 문제와 원인

정적 라우트로는 전환이 불가능하다 — 라우팅 규칙이 `application.yml`에 선언돼 있으면 대상을 바꾸는 데 재시작이 필요하고, 그건 무중단이 아니다. 그리고 더 깊은 문제가 하나 있다: 배포 agent의 락 재확인과 gateway의 라우트 갱신은 별개 부작용이라, 재확인 직후 lease를 잃은 낡은(stale) 실행자의 write가 최종 라우트로 남을 수 있다. 분산 락의 고전적 문제이고, ADR-031 BG-4가 이를 "fencing token을 검증하는 곳은 자원(sink) 쪽이어야 한다"로 정리했다.

## 왜 SCG이고 nginx가 아닌가

ADR-031의 채택 논리는 한 문장이다: **전환은 "어느 인스턴스로 보내나"를 바꾸는 일이고, 그 결정은 이미 SCG가 라우트로 내리고 있다.** 별도 스왑 기구를 새로 만들면 도구가 늘고, 공유 엣지를 매 배포마다 건드리면 설정 오류의 반경이 남의 도메인까지 커진다.

기각된 대안은 셋이다. 큐 기반 라우팅은 모든 요청에 큐 홉 지연을 상시로 붙인다 — 전환 제어는 큐 없이 드레인으로 성립하고, 가시성은 큐가 아니라 트레이싱의 몫이다. 엣지 nginx upstream 전환은 내부 서비스 전환을 위해 공유 엣지를 건드리는 반경이 크다. compose 포트 스왑은 스왑 순간의 원자성이 약하고 라우팅 결정을 두 곳으로 흩는다.

구현 후 사용자가 "서비스별 nginx를 앞에 두고 conf를 고쳐 전환하는 방식은 어떤가"를 물어 재평가한 기록도 남아 있다. 결론은 "나쁘지 않으나 이득 없음" — nginx reload가 드레인을 내장한다는 장점은 인정하되, **fencing 검증을 sink에 둘 수 없다**는 점(conf 파일을 쓰는 쪽이 곧 실행자라 sink가 거부할 지점이 없다)과 운영 대상 +3, 홉 증가가 결정적이었다. 실서버 무중단 관측 1822건이 SCG안을 실증한 뒤였다.

부수 발견도 있었다: SCG 채택 자체의 정본이 그때까지 어디에도 없었다. 워킹 스켈레톤이 대화 결정만으로 서 있었고, ADR-031이 뒤늦게 그 정본을 세우면서 ADR-024의 구 문면("nginx upstream 전환")을 취소선으로 보존한 채 명시 재판정으로 교체했다 — 재판정을 "정밀화"로 표기하려다 리뷰에 걸려 정정된 것까지 기록에 있다.

## 구현 — 상태의 정본을 한 곳에

라우트 상태(활성 슬롯 + 마지막 수락 token)의 정본은 `CoreRouteRegistry` 하나다. gateway는 이 스냅샷 하나만 보고 라우트를 만들고, 조회 API도 같은 스냅샷을 읽는다. 라우트 변경은 전환 API 한 경로로만 들어온다:

```kotlin
// routeswitch/CoreRouteRegistry.kt:94-99
// 라우트 변경은 /internal/routes/core/switch 한 경로로만 들어온다 — 임의 write는 막는다.
override fun save(route: Mono<RouteDefinition>): Mono<Void> =
    Mono.error(UnsupportedOperationException("core route is switched via /internal/routes/core/switch"))
```

전환은 단조 fencing token으로 보호된다 — 같은 token 재요청은 멱등 재시도로 수락하고, 작은 token만 거부한다:

```kotlin
// routeswitch/RouteSwitchService.kt:62-67 (발췌)
fun switchTo(target: Slot, token: Long): Result = synchronized(writeLock) {
    val before = registry.snapshot()
    if (token < before.lastAcceptedToken) {
        return Result.Stale(before.lastAcceptedToken)
    }
    ...
```

교체 순서는 write-ahead다: 목표 상태를 파일에 먼저 남기고, 라우트를 원자 교체하고, **요청을 실제로 태우는 캐시를 다시 읽어** 반영을 확인한다(우리 상태만 다시 읽는 것은 자기증명이다). 반영 확인이 실패하면 이전 슬롯으로 되돌리고, 원복까지 확인돼야 "미전환 보증"을 답한다:

```kotlin
// routeswitch/RouteSwitchService.kt:84-107 (발췌)
// ⑴ write-ahead: 라우트를 바꾸기 전에 목표 상태를 먼저 남긴다(기록 실패 = 전환 시작 안 함).
stateStore.write(CoreStateStore.State(target, token))
// ⑵ 원자 교체 + 반영 확인
registry.replace(CoreRouteRegistry.Snapshot(target, token))
if (refreshAndAwait(expected)) { ... return Result.Applied(target, token) }
// ⑶ 반영 확인 실패 → 원복. 원복까지 확인돼야 "미전환 보증"을 답할 수 있다.
return if (rollback(before, token)) Result.RolledBack(reason)
       else Result.Indeterminate("$reason; rollback not verified")
```

## 리뷰가 설계를 바꾼 지점 — 이 PR의 하이라이트

듀얼 리뷰 채택 13건 중 4건이 설계 자체를 바꿨다. 가장 큰 것은 **실패 응답의 보증 계약**이다. 리뷰 전 agent는 전환 실패(409든 500이든 전송 실패든)를 일괄 "미전환"으로 접고 유휴 슬롯을 내렸다. 그런데 409는 소유권 상실 신호다 — 그 순간 내려가는 슬롯은 새 소유자(승자)가 방금 올린 쪽일 수 있다. 그래서 실패 응답에 상태를 싣는 계약이 신설됐다:

```kotlin
// routeswitch/InternalRouteController.kt:25-28 (계약 주석)
// 실패 응답 계약: 호출자(배포 agent)는 "미전환이 보증되는 실패"와 "실상태 불명 실패"를
// 구별해야 한다 — 불명인데 미전환으로 오판하면 서비스 중인 slot을 내리게 된다.
// NOT_ATTEMPTED(409) · ROLLED_BACK(500) 은 미전환 보증, INDETERMINATE(500) 은 재확인 필요.
```

agent는 보증 없는 실패에 어느 슬롯도 내리지 않는다. 이 계약으로 ADR-031이 v1.2로 개정됐다.

나머지 셋: 원복이 스냅샷 전체를 복구해 token 최고수위가 후퇴하던 것을 슬롯만 복구하도록 정정(stale 재수락 차단), 재시작이 env 기본값으로 복귀해 죽은 슬롯을 가리키던 것을 `CoreStateStore`(write-ahead 상태 파일)로 해소, 조회가 전환 임계구역 밖에서 잠정 상태를 노출하던 것을 직렬화. 상태 파일은 부재만 env 폴백을 허용하고 손상·I/O 오류는 기동을 거절한다 — 손상을 조용히 접으면 이미 내려간 슬롯을 가리키고 지나간 token을 다시 수락하게 되기 때문이다.

검증은 테스트 21건과 실서버 무중단 관측 누계 5,475건 실패 0(배포 관통 3회 포함), 부분 실패 1회의 안전 거동(배포 미시작·부작용 0·락 해제)이었다.

## 다음으로의 연결

이 PR의 알려진 잔여가 그대로 다음 이슈가 됐다: `/internal` 무인증(fencing은 순서 보호지 인가가 아니다) → #6, CI가 테스트를 안 돌림 → PR #5, 재기동 교체 경로 → #3/#4. 무중단 관측 5,475건은 BG-5("SCG 동적 갱신이 진행 중 요청을 끊지 않는가") 판정의 입력으로 쌓였다.
