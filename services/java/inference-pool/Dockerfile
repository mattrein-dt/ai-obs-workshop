FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

COPY services/java/inference-pool/pom.xml .
COPY services/java/inference-pool/src ./src

RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.4.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"
EXPOSE 8081 9090
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
