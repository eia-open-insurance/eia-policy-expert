FROM eclipse-temurin:22-jre

WORKDIR /app
COPY build/libs/eia-policy-expert-*-SNAPSHOT.jar app.jar

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
