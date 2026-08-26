FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY app/pom.xml .
COPY app/src ./src
COPY app/lombok.config ./lombok.config
RUN mvn clean package -DskipTests -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads
ENV PORT=8081
ENV TZ=Asia/Kolkata
EXPOSE ${PORT}
ENTRYPOINT ["java", "-Xmx256m", "-Duser.timezone=Asia/Kolkata", "-jar", "app.jar", "--server.port=${PORT}"]
