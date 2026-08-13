# syntax=docker/dockerfile:1

# 실행 이미지만 만든다 — 빌드 스테이지 없음.
# bootJar 는 CI(GitHub Actions)의 `gradlew build` 가 이미 만들었고(컴파일 1회 — test+bootJar
# 한 번에), 그 산출물을 빌드 컨텍스트에서 COPY 한다. 이미지 안에서 gradle 재컴파일은 하지
# 않는다(과거엔 이 Dockerfile 이 clean bootJar 를 돌려 컴파일이 총 2회였다).
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# CI 가 만든 bootJar 산출물만 가져온다.
# plain jar 는 `-SNAPSHOT-plain.jar` 라 이 glob(`*-SNAPSHOT.jar`)에 걸리지 않는다.
COPY build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
