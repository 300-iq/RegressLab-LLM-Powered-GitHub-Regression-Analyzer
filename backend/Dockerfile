# Java 21 runtime, pinned multiarch image; compile the JAR on the host first.
FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN groupadd --gid 10001 regresslab && useradd --uid 10001 --gid 10001 --no-create-home regresslab
WORKDIR /app
COPY --chown=10001:10001 backend/build/libs/regresslab-api.jar /app/regresslab-api.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/regresslab-api.jar"]
