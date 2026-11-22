FROM eclipse-temurin:21.0.13.1_2-jdk-noble AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -DskipTests -Dmaven.resolver.transport=wagon -Dmaven.wagon.http.retryHandler.count=3 package

FROM eclipse-temurin:21.0.13.1_2-jre-alpine-3.24@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70
RUN apk upgrade --no-cache && apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/carecontinuo-api-0.1.0-SNAPSHOT.jar /app/carecontinuo.jar
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/carecontinuo.jar"]
