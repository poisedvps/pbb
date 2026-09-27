# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY settings.xml pom.xml ./
RUN mvn -s settings.xml -B -q dependency:go-offline
COPY src ./src
RUN mvn -s settings.xml -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
RUN useradd -r -u 10001 pbb
COPY --from=build /src/target/pbb.jar app.jar
USER pbb
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
