import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "eco"
version = "1.0.0"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // reading MP3 and OGG files (WAV is built into Java)
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // sample files made by the build machine (mp3/ogg), see the workflow
    environment("ECO_TEST_MEDIA", System.getenv("ECO_TEST_MEDIA") ?: "")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

compose.desktop {
    application {
        mainClass = "eco.MainKt"
        // lean JVM: Eco sits next to games all day
        jvmArgs += listOf(
            "-Xms24m", "-Xmx384m", "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
            "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxMetaspaceSize=160m", "-Xss512k",
            "-Dfile.encoding=UTF-8"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Eco"
            // a new number every build, so the installer updates over the previous one
            packageVersion = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
            description = "Eco - sons para chamadas do Discord"
            vendor = "Guilherme"
            modules("java.desktop", "java.logging", "jdk.unsupported")
            windows {
                menuGroup = "Eco"
                shortcut = true
                dirChooser = true
                upgradeUuid = "8d3c6a41-2f7e-4c55-a0b9-5e1f3c7d2b64"
                iconFile.set(project.file("icon/eco.ico"))
            }
        }
    }
}
