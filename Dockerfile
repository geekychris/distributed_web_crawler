FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/distributed-crawler-1.0-SNAPSHOT.jar ./crawler.jar
ENTRYPOINT ["java", "-jar", "crawler.jar"]
