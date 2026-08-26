FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/* && \
    mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S crawler && adduser -S crawler -G crawler
WORKDIR /app
COPY --from=build /src/target/distributed-crawler-1.0-SNAPSHOT.jar ./crawler.jar
USER crawler
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q -O- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "crawler.jar"]
