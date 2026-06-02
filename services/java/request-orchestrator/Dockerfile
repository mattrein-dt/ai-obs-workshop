FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY services/java/request-orchestrator/pom.xml .
COPY services/java/request-orchestrator/src ./src
COPY proto /proto

RUN apk add --no-cache maven && \
    mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
