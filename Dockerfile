# Build using the checked-in Gradle wrapper, not a mutable global Gradle install.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /work
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar && \
    cp "$(find build/libs -maxdepth 1 -name '*.jar' -type f | head -1)" /tmp/mentor.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -g 10001 mentor && useradd -r -u 10001 -g mentor mentor
WORKDIR /opt/mentor
RUN mkdir /opt/mentor/data && chown mentor:mentor /opt/mentor/data
COPY --from=build --chown=mentor:mentor /tmp/mentor.jar /opt/mentor/mentor.jar
USER mentor
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/health >/dev/null || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/opt/mentor/mentor.jar"]
