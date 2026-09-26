# Сборка
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# в Windows-клоне у gradlew нет бита исполнения
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# Запуск
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/shtab-klassa.jar app.jar
# H2-файл базы: ./data/shtab относительно /app - вынесен в volume, чтобы переживал пересоздание контейнера
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
