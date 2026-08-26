# Single-stage build to reduce memory usage on Render free tier
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads
ENV PORT=8081
ENV TZ=Asia/Kolkata
EXPOSE ${PORT}
ENTRYPOINT ["java", "-Xmx256m", "-Duser.timezone=Asia/Kolkata", "-jar", "app.jar", "--server.port=${PORT}"]
