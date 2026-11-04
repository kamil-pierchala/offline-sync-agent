FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY . .

RUN mvn clean install -DskipTests && mvn clean package spring-boot:repackage -pl sync-server -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/sync-server/target/sync-server-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]