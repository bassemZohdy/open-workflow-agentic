FROM maven:3.9.11-eclipse-temurin-21
WORKDIR /app
COPY quarkus-app/ /app/
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/quarkus-run.jar"]
