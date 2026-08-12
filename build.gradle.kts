plugins {
    // Spring Boot 3.4.x + Kotlin. 버전 근거는 README「스택 선택」.
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.junbank"
version = "0.1.0-SNAPSHOT"

// Spring Cloud BOM(Boot 3.4 호환 릴리스 트레인)로 클라우드 의존성 버전을 관리한다.
extra["springCloudVersion"] = "2024.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

dependencies {
    // 순수 라우팅용 reactive 게이트웨이(WebFlux 기반) + 자기 준비성 확인용 actuator.
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // 테스트: WebTestClient·JUnit5·AssertJ. 스텁 백엔드는 게이트웨이가 이미 쓰는
    // reactor-netty HttpServer로 띄운다(모의 서버 라이브러리를 따로 들이지 않는다).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}
