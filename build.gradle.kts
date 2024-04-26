group = "me.kroltan"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://jitpack.io")
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
    implementation("dev.kord:kord-core:0.13.1")
    implementation("dev.kord:kord-core-voice:0.10.0")
    implementation("dev.arbjerg:lavaplayer:2.1.1")
    implementation("com.github.lavalink-devs.lavaplayer-youtube-source:plugin:1.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.13")
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