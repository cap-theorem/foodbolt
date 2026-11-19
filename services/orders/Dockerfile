FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/orders.jar /app/orders.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xss512k", "-jar", "/app/orders.jar"]
