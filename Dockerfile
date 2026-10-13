FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ARG JAR_FILE=target/scheduler-app-0.0.1-SNAPSHOT.jar
COPY --chown=10001:10001 ${JAR_FILE} /app/scheduler.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/scheduler.jar"]
