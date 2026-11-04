FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q --no-transfer-progress dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S trimly && adduser -S trimly -G trimly
WORKDIR /app
COPY --from=build --chown=trimly:trimly /build/target/*.jar app.jar
USER trimly
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
