# PR #7 — CI 정비: 빌드 1회화와 wrapper 검증

이 문서는 gateway CI에서 컴파일이 두 번 돌던 것을 한 번으로 줄이고 공급망 방어를 더한 작은 작업을 다룬다. 낮은 stakes지만 배포 파이프라인의 입력이라 "테스트 실패 = 발행 차단" 순서를 깨지 않는 게 조건이었다.

## 문제와 원인

CI가 `gradlew test`로 컴파일하고, 그 뒤 Dockerfile의 빌드 스테이지가 `gradlew clean bootJar`로 **또 컴파일**했다. 같은 소스를 두 번 빌드하니 잡 시간이 배로 들었다. 원인은 구조에 있다 — Dockerfile이 이미지 안에서 gradle을 돌려 jar를 만드는 멀티스테이지였다.

## 채택 — 스텝 순서가 곧 계약

`gradlew build`는 test와 bootJar를 한 번에 만든다(컴파일 1회). 그 산출 jar를 이미지가 재사용하도록 Dockerfile을 빌드 스테이지 없이 CI가 만든 jar를 COPY하는 단일 run 스테이지로 바꿨다. wrapper 검증은 `gradle/actions/wrapper-validation`을 gradlew 실행 **전**에 둬서 변조된 래퍼가 도는 것을 막았다.

```yaml
# build.yml (발췌) — 순서 자체가 보증 수단
- name: Gradle wrapper 검증       # 변조 래퍼 차단 (gradlew 실행 전)
  uses: gradle/actions/wrapper-validation@v4
- name: JDK 21 셋업 ...
- name: 빌드 (test + bootJar)      # 컴파일 1회
  run: ./gradlew --no-daemon build
- name: GHCR 로그인 ...            # test 실패 시 여기 도달 못 함
```

```dockerfile
# Dockerfile (발췌) — 재컴파일 제거
# bootJar 는 CI 의 gradlew build 가 이미 만들었다(컴파일 1회). 이미지 안에서 다시 빌드하지
# 않는다(과거엔 이 Dockerfile 이 clean bootJar 를 돌려 컴파일이 총 2회였다).
FROM eclipse-temurin:21-jre AS run
COPY build/libs/*-SNAPSHOT.jar app.jar
```

"테스트 실패 = 발행 차단" 순서는 유지된다 — `build`가 test를 포함하고 login·push 앞단이라, test가 실패하면 잡이 중단되고 이미지가 발행되지 않는다. 별도 게이트가 필요 없다.

## 검증 — 그린 위장 자가 점검

로컬 확인을 `--rerun-tasks`로 강제했다 — Gradle이 입력이 안 바뀌면 테스트를 캐시로 건너뛰고 성공을 보고하는 UP-TO-DATE 함정을 스스로 배제한 것이다. 이미지 기동 스모크(actuator UP)로 CI가 만든 jar가 정상 실행됨을 확인했다. 잡 시간 전후 비교는 CI 실행에서만 가능해 이연했고, 머지 후 실측에서 이전 ~160초 → **88초**(약 45% 단축)로 확인됐다.

## 다음으로의 연결

빌드 1회화의 잔여(컴파일 2회 비용)가 이 이슈로 닫혔다. 낮은 stakes라 셀프체크로 채택했고(듀얼 리뷰 생략·가역), 이걸로 G2 마일스톤(#6 인가·#7 CI)이 완주됐다.
