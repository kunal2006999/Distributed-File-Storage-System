FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
# Even though you run on 8081/8082 locally,
# inside the container, the app usually defaults to 8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]