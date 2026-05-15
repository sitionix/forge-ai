FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY boot/pom.xml boot/pom.xml
COPY api-rest/pom.xml api-rest/pom.xml
COPY application/pom.xml application/pom.xml
COPY domain/pom.xml domain/pom.xml
COPY infrastructure/pom.xml infrastructure/pom.xml
COPY infrastructure/mongodb/pom.xml infrastructure/mongodb/pom.xml
COPY infrastructure/codex-cli/pom.xml infrastructure/codex-cli/pom.xml
COPY jacoco-report/pom.xml jacoco-report/pom.xml
COPY boot/src boot/src
COPY api-rest/src api-rest/src
COPY application/src application/src
COPY domain/src domain/src
COPY infrastructure infrastructure

RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -pl boot -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/boot/target/boot-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 9099
ENTRYPOINT ["java","-jar","/app/app.jar"]
