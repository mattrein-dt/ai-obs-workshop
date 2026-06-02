FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY services/java/prompt-cache/pom.xml .
COPY services/java/prompt-cache/src ./src
COPY proto /proto

RUN apk add --no-cache maven && \
    mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 50051 9090
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
