# PR #4 — gateway CD: 네 번째 배포 대상이 되다

이 문서는 gateway가 수동 부트스트랩에서 정식 배포 경로 안으로 들어온 과정을 다룬다. 블루-그린 전환 구현(PR #2)으로 gateway는 배포 대상 4번째가 됐는데, 정작 자신은 CI(이미지 빌드)만 있고 CD가 없어 .9 서버에서 jar 볼륨 방식으로 떠 있었다. 이 작업은 infra의 OIDC repo별 allowlist(infra#28)와 한 슬라이스로 묶였다 — 두 번째 repo가 실제로 배포를 요청해 봐야 "repo가 자기 target만 배포한다"는 결박이 검증되기 때문이다.

## 문제와 원인

배포 대상이 된 서비스가 수동 부트스트랩으로 떠 있으면 배포 경로가 두 벌이 된다. 그리고 gateway에는 구조적 특수성이 있다: 블루-그린 전환 수단(SCG 라우트)의 소유자가 자기 자신이라, 자기 배포를 블루-그린으로 할 수 없다(자기 참조). 이 판단은 ADR-027 DO-20에서 이미 내려져 있었다 — **재기동 교체**로 배포하고 짧은 중단을 수용한다. 워크플로 머리에 그 논리가 그대로 적혀 있다:

```yaml
# .github/workflows/deploy.yml:19-23 (발췌)
# ⚠️ target=gateway 의 배포 방식은 코어와 다르다 — 재기동 교체이지 블루-그린이 아니다
#    (ADR-027 DO-20 v0.5 ⓐ): 게이트웨이는 블루-그린 전환 수단(SCG 라우트)의 소유자라
#    자기 자신의 전환을 자기가 할 수 없다(자기 참조). ... 이 워크플로가 만드는 요청의
#    계약은 코어와 같고, 무중단 여부는 agent 쪽 배포 모드가 정한다.
```

## 채택 — 계약은 core와 같게, 배포 모드만 다르게

워크플로가 만드는 것은 서명된 요청 하나뿐이고, 실행 주체는 agent 하나다. 그래서 CD는 core의 deploy.yml을 원형으로 복제해 계약을 바이트 수준으로 같게 유지했다 — 다른 곳은 이미지 저장소·target·동시성 그룹 등 7곳뿐이다. 서명 계약(HMAC canonical 문자열과 OIDC claim 행렬)은 파일 머리에 명시돼 있고, 특히 `job_workflow_ref` 결박은 이 파일의 경로·이름·브랜치가 agent 설정과 정확히 일치해야 한다는 운영 계약을 만든다:

```yaml
# 같은 파일 :14-17 (발췌)
# ⚠️ job_workflow_ref 결박: 이 파일 경로·이름(deploy.yml)·브랜치(main)가 agent의
#    OIDC_JOB_WORKFLOW_REF=jun-bank/gateway/.github/workflows/deploy.yml@refs/heads/main 과
#    정확히 같아야 게이트 2를 통과한다. 파일명·위치를 바꾸면 agent 설정도 함께 바꿔야 한다.
```

배포 요청은 동시에 하나만 돌도록 직렬화하고(`concurrency.group: deploy-gateway`), 응답은 2xx만 성공으로 인정한다 — "조용한 성공 위장 금지"가 주석에 그대로 있다. core에서 발견된 입력 하드닝(주입·4항 가드)은 신규 파일에 처음부터 포함됐다.

allowlist 쪽에서는 선검증이 설계 하나를 뒤집었다: 단일 env 모드(allowlist 없이 한 target만 허용하는 하위호환)를 남기려던 것을 "무결박 우회 모드의 존속"이라는 지적으로 폐지하고, 1항목 allowlist로 정규화했다.

## 검증 — e2e 3종, 그리고 뜻밖의 실증

이 슬라이스의 종료 조건은 e2e 3종이었다.

**양성(core)**: allowlist를 경유한 정상 배포와 블루-그린 전환.

**음성**: 실제 프로덕션 경로로 "core 신원이 gateway target을 요청"하는 위반을 만들었다. 1차 시도가 뜻밖의 실증이 됐다 — allowlist 한 줄만 스왑했더니 agent가 "target을 두 저장소가 주장한다"며 **기동 자체를 거부**했다(1:1 결박 검증이 설계대로 동작). 교차 스왑으로 다시 만든 위반 요청은 HTTP 403과 이력 `REJECTED`/`TARGET_FORBIDDEN`으로 거절됐고, 원장 선점 0행 — 부작용이 전혀 없었다.

**양성(gateway)**: 구조적으로 머지 후에만 가능하다 — deploy.yml이 main에 있어야 `workflow_run` 트리거와 `job_workflow_ref` 결박이 성립한다. 머지 후 첫 실행에서 배포 모드 부재로 fail-closed(설계대로), 모드 등록 후 재발화로 HTTP 200 배포 완주.

그리고 이 배포가 PR #2의 상태 파일 수정을 실전 검증했다: .9의 gateway를 이미지 기반으로 전환하고 재기동했을 때, 활성 슬롯이 env 기본값(blue)이 아니라 **파일값(green, token 9)으로 복원**됐다 — "state restored from file" 로그가 그 증거다.

## 다음으로의 연결

이 PR로 gateway가 CD 경로 안에 들어왔고, 이후 PR #5(CI 테스트)·PR #9(compose 동봉)가 같은 파일 위에 쌓인다. agent의 allowlist에는 gateway의 repo 수치 ID가 등재되어 repo↔target 1:1 결박이 두 항목으로 실동작하기 시작했다.
