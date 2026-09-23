FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/build/libs/media-factory-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-Djava.awt.headless=true","-jar","app.jar"]
