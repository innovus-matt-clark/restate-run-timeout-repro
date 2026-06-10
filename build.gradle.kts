plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.devtools.ksp") version "2.3.0"
}

repositories {
    mavenCentral()
}

// Override with: ./gradlew test -PrestateVersion=2.4.1
val restateVersion = providers.gradleProperty("restateVersion").getOrElse("2.8.0")

dependencies {
    implementation("dev.restate:sdk-api-kotlin:$restateVersion")
    implementation("dev.restate:sdk-serde-kotlinx:$restateVersion")
    ksp("dev.restate:sdk-api-kotlin-gen:$restateVersion")
    implementation("dev.restate:client-kotlin:$restateVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("dev.restate:sdk-testing:$restateVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
