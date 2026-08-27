FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/* && \
    mvn -q -B dependency:go-offline
# Node/npm are NOT installed via apt on purpose — Debian's npm is old enough
# that Vaadin's version check fails ("npm 9.2.0 has known problems"). The
# vaadin-maven-plugin's build-frontend goal will download a pinned Node/npm
# into node_modules on first run.
COPY src ./src
COPY frontend ./frontend
COPY types.d.ts vite.config.ts tsconfig.json package.json ./
# -Pproduction runs vaadin-maven-plugin's prepare-frontend + build-frontend,
# so the jar ships a bundled UI and boots without dev-mode source lookup.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests -Pproduction package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S crawler && adduser -S crawler -G crawler
WORKDIR /app
COPY --from=build /src/target/distributed-crawler-1.0-SNAPSHOT.jar ./crawler.jar
USER crawler
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q -O- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "crawler.jar"]
