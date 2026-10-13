
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home appuser

COPY build/libs/*.jar app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
