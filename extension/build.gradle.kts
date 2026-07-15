plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.22"
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

repositories {
    mavenCentral()
}

val jebHome: String by project

dependencies {
    compileOnly(fileTree(mapOf(
        "dir" to "$jebHome/bin/app",
        "include" to listOf("*.jar")
    )))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("kfc")
    archiveClassifier.set("")
    archiveVersion.set("0.1.4")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
