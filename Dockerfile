# syntax=docker/dockerfile:1

# --- build stage ---------------------------------------------------------
# Gradle wrapper 로 bootJar 를 만든다(self-contained — 호스트 JDK/Gradle 불필요).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 래퍼·빌드 스크립트 먼저 복사해 의존성 레이어를 캐시한다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 실행 가능 jar 빌드.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# --- run stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# bootJar 산출물만 가져온다(plain jar 제외).
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
