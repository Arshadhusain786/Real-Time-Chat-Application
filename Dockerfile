# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY app/pom.xml .
RUN mvn dependency:go-offline -q
COPY app/src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads

ENV PORT=8081
ENV TZ=Asia/Kolkata
EXPOSE ${PORT}

ENTRYPOINT ["java", "-Duser.timezone=Asia/Kolkata", "-jar", "app.jar", "--server.port=${PORT}"]
