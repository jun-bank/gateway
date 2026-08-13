# gateway 배포·전환 축 개발 여정

이 폴더는 gateway repo의 배포(CD)와 블루-그린 전환 축 개발 과정을 PR 단위로 기록한 문서 모음이다. 각 문서는 문제의 원인, 검토한 선택지, 채택 이유, 그리고 코드로의 구현 과정을 순서대로 담는다.

## 이 저장소의 배포 축 역할

gateway는 Spring Cloud Gateway 기반의 진입점이면서, 동시에 **core 블루-그린 전환의 실행 지점**이다. core의 블루·그린 두 슬롯 중 어디로 트래픽을 보낼지는 gateway의 동적 라우트가 정하고, 배포 agent가 전환 시점에 gateway의 내부 API를 호출해 라우트를 바꾼다. 전환 요청은 단조 증가하는 fencing token으로 보호되고, 활성 슬롯은 상태 파일로 재기동 너머까지 보존된다.

gateway 자신의 배포는 core와 달리 **재기동 교체**다 — 자기 자신의 라우트 전환을 자기가 할 수 없어서다(ADR-027 DO-20 ⓐ). 그래서 슬롯이 없고 compose도 한 벌이며, 배포 중 짧은 중단을 허용하는 대신 구조가 단순하다. 이 결정의 정본은 ADR-031(BG-1~BG-5)과 ADR-030이다.

## 문서 목록 (PR 순서)

| PR | 이슈 | 문서 | 한 줄 |
|---|---|---|---|
| #2 | #1 | [pr-02-dynamic-route.md](pr-02-dynamic-route.md) | 동적 라우트 + 전환 내부 API — fencing token |
| #4 | #3 | [pr-04-gateway-cd.md](pr-04-gateway-cd.md) | gateway CD — 네 번째 배포 대상이 되다 |
| #5 | — | [pr-05-ci-test.md](pr-05-ci-test.md) | 이미지 발행 전 테스트 — 발행 관문 |
| #9 | — | [pr-09-compose-embed.md](pr-09-compose-embed.md) | 정본 compose 신설 + manifest 동봉 |

infra 쪽의 전체 여정은 infra repo의 `docs/devlog/`, 아키텍처 해설은 docs repo의 `study/tech/infra-journey/`에 있다.
