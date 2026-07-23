# -------- Build stage --------
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /app

COPY pom.xml .

COPY src ./src
RUN mvn clean package -DskipTests


# -------- Runtime stage --------
FROM amazoncorretto:25-jdk
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

#USER nonroot

ENTRYPOINT ["java","-jar", "app.jar"]

EXPOSE 8080