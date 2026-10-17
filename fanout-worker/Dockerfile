# Stage 1: build the jar with the Maven wrapper.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN ./mvnw -B -DskipTests package

# Stage 2: run on a JRE as a non-root user.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S fanout && adduser -S fanout -G fanout
WORKDIR /app
COPY --from=build /workspace/target/fanout-worker-0.0.1-SNAPSHOT.jar app.jar
USER fanout
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
