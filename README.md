# jun-bank-gateway

jun-bank 내부 API 게이트웨이. 지금은 도메인 서버 세 개(core · settlement · ledger)로의
**프록시**와, core 서버의 **블루-그린 전환**을 한다. 채널별(모바일/웹) 라우팅과
인증/인가는 이 위에 얹을 후속 작업이며, 아직 없다.

## 스택

- Kotlin 2.1.0 / JDK 21
- Spring Boot 3.4.5
- Spring Cloud 2024.0.1 (Spring Cloud Gateway, reactive/WebFlux)
- Gradle 8.14.4 (wrapper 포함)

## 라우팅

| 요청 경로 | 대상 | 전달 경로 |
|---|---|---|
| `/core/**` | 활성 slot의 URI (아래 「블루-그린 전환」) | `StripPrefix=1` 로 `/core` 제거 |
| `/settlement/**` | `http://settlement:8080` | `StripPrefix=1` |
| `/ledger/**` | `http://ledger:8080` | `StripPrefix=1` |

settlement · ledger 규칙은 `src/main/resources/application.yml`
(`spring.cloud.gateway.routes`)에 선언한다. 도메인 서버는 docker compose 서비스명을
host 로, 포트 8080 으로 뜬다고 가정한다. 전환 대상인 core 라우트만 프로그램 라우트로
관리한다(`routeswitch` 패키지).

예: `GET /core/health` → core 서버의 `/health`. 트레일링 슬래시가 없는 `GET /core` 도
`/core/**` 에 매칭되어 도메인 서버의 루트 `/` 로 라우팅된다(StripPrefix 가 프리픽스
세그먼트를 제거하면 빈 경로는 `/` 로 정규화된다).

게이트웨이 자신의 준비성은 `GET /actuator/health` 로 확인한다.

## 블루-그린 전환

core 라우트는 재시작 없이 blue ↔ green 으로 바꿀 수 있다. slot → URI 매핑은 환경변수가
소유하고, 게이트웨이는 지금 어느 slot이 활성인지만 관리한다.

| 환경변수 | 기본값 |
|---|---|
| `CORE_BLUE_URI` | `http://core:8080` |
| `CORE_GREEN_URI` | `http://core-green:8080` |
| `CORE_ACTIVE_SLOT` | `blue` (상태 파일이 없을 때의 기동 시점 활성 slot) |
| `CORE_STATE_FILE` | (없음 — 설정하면 상태를 그 파일에 지속화한다, 아래) |

기본값은 다른 라우트와 같은 규칙, 즉 compose 서비스명 host + 포트 8080 이다. core를
호스트에서 직접 띄우고 호스트 포트로 배선하는 환경(예: `host.docker.internal:13001/13002`
같은 `.9`식 배선)은 **env로 명시 주입**한다 — 기본값에 그 배선을 넣지 않는다.

```sh
CORE_BLUE_URI=http://host.docker.internal:13001 \
CORE_GREEN_URI=http://host.docker.internal:13002 \
docker run ... jun-bank-gateway
```

전환은 `/internal` 관리 API로 받는다. 이 프리픽스는 어떤 라우트에도 걸리지 않아 외부로
프록시되지 않는다. v1은 인증 계층을 두지 않는다 — **`/internal` 은 LAN 경계(호스트
방화벽·NAT)가 지키는 표면이며, fencing token은 stale 실행자의 순서를 결박하는 장치이지
인가(authorization)가 아니다.** 인증은 후속 결정이다.

```sh
# 현재 상태
curl localhost:8080/internal/routes/core
# {"service":"core","activeSlot":"blue","uri":"http://core:8080","lastAcceptedToken":3}

# 전환
curl -XPOST localhost:8080/internal/routes/core/switch \
  -H 'Content-Type: application/json' \
  -d '{"targetSlot":"green","fencingToken":7}'
# {"service":"core","activeSlot":"green","fencingToken":7}
```

`fencingToken` 계약은 **int64 양수(≥1)** 이고 필수다(없거나 0 이하면 400). 마지막으로
수락한 token보다 작으면 stale 실행자의 지시로 보고 409로 거부한다 — 같은 token 재요청은
멱등 재시도로 수락한다.

### 실패 응답의 `state` — 내려도 되는 상태인가

전환 호출자(배포 스크립트/agent)는 "미전환이 보증되는 실패"와 "실상태 불명 실패"를
구별해야 한다. 불명인데 미전환으로 오판하면 지금 서비스 중인 slot을 내리게 된다.
그래서 실패 응답 body에는 `state` 가 실린다(성공 200 형태는 그대로다).

| 상태 | HTTP | 뜻 | 호출자가 할 일 |
|---|---|---|---|
| `NOT_ATTEMPTED` | 409 | stale token — 전환 시도 자체가 없었다 | 미전환 보증. token을 갱신해 재시도 |
| `ROLLED_BACK` | 500 | 시도했으나 원복했고, 원복 반영까지 확인했다 | 미전환 보증. 기존 slot 유지 |
| `INDETERMINATE` | 500 | 원복이 확인되지 않았거나 도중 예외 — 실상태 불명 | `GET /internal/routes/core` 로 재확인. **어느 slot도 성급히 내리지 않는다** |

```json
{"error":"route refresh did not take effect","state":"ROLLED_BACK"}
```

원복은 slot만 되돌리고 token은 `max(이전 수락값, 시도한 값)` 으로 유지한다 — 되돌리면서
token까지 낮추면 그 사이 값을 든 stale 실행자가 나중에 수락된다.

### 상태 지속화 (`CORE_STATE_FILE`)

설정하지 않으면 상태는 인메모리다 — 게이트웨이가 재시작하면 `CORE_ACTIVE_SLOT` · token 0
으로 리셋되고, 그 짧은 창에서 이미 지나간 낮은 token이 한 번 수락될 수 있다(알려진 리스크).

경로를 주면 `{활성 slot, 마지막 수락 token}` 을 그 파일에 남기고 기동 시 복원한다(파일이
`CORE_ACTIVE_SLOT` 보다 우선하며, 파일이 없거나 깨졌으면 env 기본으로 뜬다).

```sh
CORE_STATE_FILE=/var/lib/jun-bank/gateway/core-state   # 재시작을 넘겨 쓰려면 볼륨에
```

```
slot=green
token=12
```

기록은 **write-ahead** 다: ⑴ 파일에 목표 상태를 먼저 남기고(같은 디렉터리 임시 파일 +
fsync + rename) ⑵ 그 다음 라우트를 교체·확인한다. 파일이 항상 라우트보다 앞서므로, 어느
지점에서 죽어도 파일이 가리키는 slot은 "그 시점에 헬스 통과한 전환 목표" 아니면 "전환 전
기존 active" 둘 중 하나 — 배포 절차상 기존 slot은 전환 성공 뒤에야 내리므로 두 후보 모두
그 시점에 살아 있다. 파일에 못 남기면 라우트는 건드리지 않는다(`INDETERMINATE`).

전환은 진행 중 요청을 끊지 않는다(라우트 목록을 통째로 교체하고, 이미 라우트가 정해진
요청은 그대로 끝난다). 실측은 `RouteSwitchNoDowntimeTest`. 상태 조회(GET)는 전환과 같은
락에서 직렬화되어, 전환 진행 중에는 완료(또는 원복) 후의 상태만 보인다.

## 빌드

```sh
JAVA_HOME=/home/jun/.sdkman/candidates/java/21.0.5-tem ./gradlew build
```

## 컨테이너

멀티스테이지 Dockerfile — build(`eclipse-temurin:21-jdk` + wrapper `bootJar`) →
run(`eclipse-temurin:21-jre`). 컨테이너 포트 8080.

```sh
docker build -t jun-bank-gateway .
```

라우팅 자체는 도메인 서버 세 개가 함께 떠 있어야 실검증된다(상위 compose 담당).
