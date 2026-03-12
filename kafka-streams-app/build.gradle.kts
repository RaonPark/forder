plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.spring") version "2.3.10"
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"
description = "kafka-streams-app"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.apache.kafka:kafka-streams")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ── Integration Test: Debezium Embedded Engine (Layer 1) ─────────────────
    // Kafka Connect 없이 Debezium을 test JVM 내에서 직접 실행
    // MongoDB 변경 이벤트 포맷 검증 및 빠른 피드백
    // Debezium 3.4.x — Kafka Connect 4.1.1 기반 (Spring Boot 4.0의 Kafka 4.0과 호환)
    // Debezium 3.0.x는 Kafka Connect 3.8.0 기반이므로 Kafka 4.0과 API 비호환
    testImplementation("io.debezium:debezium-embedded:3.4.2.Final")
    testImplementation("io.debezium:debezium-connector-mongodb:3.4.2.Final")

    // ── Integration Test: TestContainers (Layer 1 + 2 공통) ───────────────────
    // kafka:   KRaft 모드 지원
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootBuildImage {
    runImage = "paketobuildpacks/ubuntu-noble-run:latest"
}

tasks.register("prepareKotlinBuildScriptModel") { }
