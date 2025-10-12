FROM eclipse-temurin:21-jdk-alpine as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/distributed-crawler-1.0-SNAPSHOT.jar ./crawler.jar
ENTRYPOINT ["java", "-jar", "crawler.jar"]
