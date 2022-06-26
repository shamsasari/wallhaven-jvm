plugins {
    kotlin("jvm") version "1.7.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(18))
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs = listOf("--add-modules=jdk.incubator.foreign")
}

application {
    mainClass.set("shamsasari.wallhavenplugin.WallhavenPlugin")
    applicationDefaultJvmArgs = listOf(
        "--add-modules=jdk.incubator.foreign",
        "--enable-native-access=ALL-UNNAMED"
    )
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")
}

tasks.shadowJar {
    archiveClassifier.set("")
}
