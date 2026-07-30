# Stage 1 — Build with Maven
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 1. Dependencies only (layer caching)
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# 2. Source code
COPY src src

# 3. Package (skip tests — tests run in CI)
RUN mvn package -DskipTests -B

# Stage 2 — Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create a non-root user for security
RUN useradd --system --no-create-home --user-group appuser

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

USER appuser

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl --connect-timeout 3 -s -o /dev/null http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
