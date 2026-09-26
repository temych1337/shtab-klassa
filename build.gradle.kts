buildscript {
    dependencies {
        // flyway-плагин гоняет миграции вне Spring, драйвер ему нужен на classpath сборки
        classpath("com.h2database:h2:2.3.232")
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.flywaydb.flyway") version "11.7.2"
}

group = "ru.shtabklassa"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// один jar с фиксированным именем - Dockerfile копирует его без масок
tasks.bootJar {
    archiveFileName = "shtab-klassa.jar"
}

tasks.jar {
    enabled = false
}

// Та же БД, что в application.yml. Путь абсолютный: относительный "./data" демон Gradle
// резолвит от своей рабочей папки (~/.gradle/daemon/...), и миграция уезжает не туда.
flyway {
    url = "jdbc:h2:file:${layout.projectDirectory.dir("data").asFile.absolutePath.replace('\\', '/')}/shtab;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE"
    user = "sa"
    password = ""
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}
