# ---- Build stage: compile the WAR ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# Resolve dependencies first for better layer caching
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests
RUN ls -la target/

# ---- Runtime stage: Tomcat + OpenTelemetry javaagent ----
FROM tomcat:10.1-jdk17

# OpenTelemetry Java agent version
ARG OTEL_AGENT_VERSION=2.31.1

# Download the OpenTelemetry javaagent (zero-code instrumentation)
RUN curl -fsSL -o /opt/opentelemetry-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

# Common JVM options; OpenTelemetry settings come from environment at runtime
ENV JAVA_OPTS="-javaagent:/opt/opentelemetry-javaagent.jar -Dotel.javaagent.configuration-file=/opt/otel.properties"

# Deploy the built WAR to Tomcat's webapps
COPY --from=build /build/target/demo-observability.war /usr/local/tomcat/webapps/

# Default OpenTelemetry configuration (overridable via env at container runtime)
COPY otel.properties /opt/otel.properties

EXPOSE 8080
