# syntax=docker/dockerfile:1.7
# ------------------------------------------------------------------------------
# F-DEFECTS-PERFORMANCE T4 — production-shaped image for invoice-generator.
# Multi-stage: builder uses the Maven wrapper to package the jar; runtime image
# carries only the JRE and the boot jar.
# ------------------------------------------------------------------------------

# ---------- Builder ----------
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and pom first so Docker caches dependency resolution.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw -B -q dependency:go-offline

# Copy the rest of the project and package without running tests (tests run in CI).
COPY src src
COPY config config
RUN ./mvnw -B -q -DskipTests package

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# A non-root user is the small but easy hardening win.
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=builder /workspace/target/*.jar /app/invoice-generator.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/invoice-generator.jar"]
