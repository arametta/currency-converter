# Single-stage build per spec. Assumes `mvn clean package -DskipTests` has
# produced the jar in ./target before `docker build` runs — that's the build
# command documented in the spec/README.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/currency-converter-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
