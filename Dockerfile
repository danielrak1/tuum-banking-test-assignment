# syntax=docker/dockerfile:1

# Build: the Gradle wrapper on a JDK, so no local Java or Gradle is needed. bootJar doesn't run the
# tests; they need Testcontainers and belong to ./gradlew check (design.md §8).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --quiet \
    && cp build/libs/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Runtime: a JRE, non-root, with dependencies and application classes in separate layers.
FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin banking
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER banking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
