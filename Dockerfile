# ──────────────────────────────────────────
# Stage 1: Build
# Gradle Wrapper + JDK 24로 서비스별 bootJar 생성
# ──────────────────────────────────────────
FROM eclipse-temurin:24-jdk-noble AS builder

ARG SERVICE_NAME=order-service

WORKDIR /workspace

# Gradle Wrapper 및 빌드 스크립트 먼저 복사 (레이어 캐시 활용)
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts ./
COPY gradle ./gradle

# common 모듈 복사 (모든 서비스의 의존성)
COPY common ./common

# 빌드 대상 서비스 소스 복사
COPY ${SERVICE_NAME} ./${SERVICE_NAME}

RUN chmod +x gradlew && \
    ./gradlew :${SERVICE_NAME}:bootJar -x test --no-daemon --stacktrace

# ──────────────────────────────────────────
# Stage 2: Run
# JRE만 포함한 경량 이미지
# ──────────────────────────────────────────
FROM eclipse-temurin:24-jre-noble

ARG SERVICE_NAME=order-service

WORKDIR /app

COPY --from=builder /workspace/${SERVICE_NAME}/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
