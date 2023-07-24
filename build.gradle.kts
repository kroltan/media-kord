group = "me.kroltan"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://m2.dv8tion.net/releases")
}

plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("jvm") version "1.9.0"
    application
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")
    implementation("io.github.milis92.kotlin_markdown:basic:1.0.0")
    implementation("dev.kord:kord-core:0.10.0")
    implementation("dev.kord:kord-core-voice:0.10.0")
    implementation("com.sedmelluq:lavaplayer:1.3.77")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(19)
}

application {
    mainClass.set("MainKt")
}