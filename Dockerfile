# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy all POM files first for dependency caching
COPY pom.xml .
COPY trace-log-core/pom.xml trace-log-core/
COPY trace-log-spring-boot-starter/pom.xml trace-log-spring-boot-starter/
COPY trace-log-bom/pom.xml trace-log-bom/
COPY trace-log-example/pom.xml trace-log-example/
COPY trace-log-e2e/pom.xml trace-log-e2e/

# Download dependencies (cached unless POMs change)
RUN mvn dependency:go-offline -B

# Copy source code
COPY trace-log-core/src trace-log-core/src
COPY trace-log-spring-boot-starter/src trace-log-spring-boot-starter/src
COPY trace-log-example/src trace-log-example/src

# Build the project (excluding e2e tests module)
RUN mvn package -B -DskipTests -pl !trace-log-e2e

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Run as non-root user
RUN groupadd --system appuser && useradd --system --gid appuser appuser

COPY --from=build /app/trace-log-example/target/*.jar app.jar

RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
