FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies to cache them
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/agent-of-oz-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 7860
RUN useradd -m -u 1000 user
USER user
ENV PORT=7860
CMD ["java", "-jar", "app.jar", "--server.port=${PORT}"]
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]