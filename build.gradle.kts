plugins {
    kotlin("jvm") version "2.2.20"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "br.com.m4trixdev"
version = "2.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("mysql:mysql-connector-java:8.0.33")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

val targetJavaVersion = 21

kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks {
    shadowJar {
        archiveFileName.set("MatrixGPT.jar")

        relocate("kotlin", "br.com.m4trixdev.libs.kotlin")
        relocate("kotlinx", "br.com.m4trixdev.libs.kotlinx")
        relocate("com.zaxxer.hikari", "br.com.m4trixdev.libs.hikari")
        relocate("okhttp3", "br.com.m4trixdev.libs.okhttp3")
        relocate("okio", "br.com.m4trixdev.libs.okio")
        relocate("com.google.gson", "br.com.m4trixdev.libs.gson")

        minimize()
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    runServer {
        minecraftVersion("1.21.4")
    }
}