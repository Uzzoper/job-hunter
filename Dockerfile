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

# Create a non-root user for security — fixed UID 1000 so bind-mounted host
# directories (./data) are writable when the host user is the first Linux user.
# The eclipse-temurin (Ubuntu 24.04) base ships an 'ubuntu' user at UID 1000 — remove it first.
RUN userdel --remove ubuntu 2>/dev/null; \
    useradd --system --no-create-home --user-group --uid 1000 appuser

# Writable runtime directories (resume PDFs and the SQLite database live here)
RUN mkdir -p /app/uploads/resumes /app/data && chown -R appuser:appuser /app/uploads /app/data

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

USER appuser

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl --connect-timeout 3 -s -o /dev/null http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
