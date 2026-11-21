FROM eclipse-temurin:21.0.9_10-jdk-jammy AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21.0.9_10-jre-jammy
WORKDIR /app
COPY --from=build /app/target/carecontinuo-api-0.1.0-SNAPSHOT.jar /app/carecontinuo.jar
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/carecontinuo.jar"]
