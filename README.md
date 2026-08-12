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
| `CORE_BLUE_URI` | `http://host.docker.internal:13001` |
| `CORE_GREEN_URI` | `http://host.docker.internal:13002` |
| `CORE_ACTIVE_SLOT` | `blue` (기동 시점의 활성 slot) |

전환은 `/internal` 관리 API로 받는다. 이 프리픽스는 어떤 라우트에도 걸리지 않아 외부로
프록시되지 않는다. v1은 인증 계층을 두지 않는다 — 접근 통제는 LAN 경계, 오작동 방지는
fencing token이 맡는다.

```sh
# 현재 상태
curl localhost:8080/internal/routes/core
# {"service":"core","activeSlot":"blue","uri":"http://...:13001","lastAcceptedToken":3}

# 전환
curl -XPOST localhost:8080/internal/routes/core/switch \
  -H 'Content-Type: application/json' \
  -d '{"targetSlot":"green","fencingToken":7}'
# {"service":"core","activeSlot":"green","fencingToken":7}
```

`fencingToken` 은 필수다(없으면 400). 마지막으로 수락한 token보다 작으면 stale 실행자의
지시로 보고 409로 거부한다 — 같은 token 재요청은 멱등 재시도로 수락한다. 이 상태는
인메모리라 게이트웨이가 재시작하면 `CORE_ACTIVE_SLOT` · token 0 으로 리셋된다.

전환은 진행 중 요청을 끊지 않는다(라우트 목록을 통째로 교체하고, 이미 라우트가 정해진
요청은 그대로 끝난다). 실측은 `RouteSwitchNoDowntimeTest`.

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
