FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# This copies your 60MB 'Fat' JAR into the container
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]