FROM deploy-server:latest
WORKDIR /app
COPY target/smart_interview-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]