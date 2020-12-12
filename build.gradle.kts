import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.20"
    // Shadow to prevent error when running `:jmhJar` Gradle task
    // which otherwise fails to find `kotlin-stdlib-jdk7/1.4.20`.
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("me.champeau.gradle.jmh") version "0.5.2"
    kotlin("kapt") version "1.4.20"
}

group = "cz.radekm"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    jcenter()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}

dependencies {
    implementation("io.reactivex.rxjava3:rxjava:3.0.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.1")

    jmhImplementation("org.openjdk.jmh:jmh-core:1.26")
    kaptJmh("org.openjdk.jmh:jmh-generator-annprocess:1.26")
}

jmh {
    // Otherwise Shadow fails.
    duplicateClassesStrategy = DuplicatesStrategy.WARN

    warmupIterations = 15
    iterations = 15
    fork = 3
    threads = 1
    jvmArgsAppend = listOf("-Xmx6g")
}
