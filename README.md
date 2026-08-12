# jun-bank-gateway

jun-bank 내부 API 게이트웨이. 지금은 **순수 라우팅** 워킹 스켈레톤이다 — 도메인 서버
세 개(core · settlement · ledger)로의 프록시만 한다. 채널별(모바일/웹) 라우팅과
인증/인가는 이 위에 얹을 후속 작업이며, 아직 없다.

## 스택

- Kotlin 2.1.0 / JDK 21
- Spring Boot 3.4.5
- Spring Cloud 2024.0.1 (Spring Cloud Gateway, reactive/WebFlux)
- Gradle 8.14.4 (wrapper 포함)

## 라우팅

라우팅 규칙은 코드가 아니라 `src/main/resources/application.yml`
(`spring.cloud.gateway.routes`)에 선언한다. 도메인 서버는 docker compose 서비스명을
host 로, 포트 8080 으로 뜬다고 가정한다.

| 요청 경로 | 대상 | 전달 경로 |
|---|---|---|
| `/core/**` | `http://core:8080` | `StripPrefix=1` 로 `/core` 제거 |
| `/settlement/**` | `http://settlement:8080` | `StripPrefix=1` |
| `/ledger/**` | `http://ledger:8080` | `StripPrefix=1` |

예: `GET /core/health` → core 서버의 `/health`. 트레일링 슬래시가 없는 `GET /core` 도
`/core/**` 에 매칭되어 도메인 서버의 루트 `/` 로 라우팅된다(StripPrefix 가 프리픽스
세그먼트를 제거하면 빈 경로는 `/` 로 정규화된다).

게이트웨이 자신의 준비성은 `GET /actuator/health` 로 확인한다.

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
