plugins {
    application
    id("com.gradleup.shadow") version "9.2.1"
    id("com.github.ben-manes.versions") version "0.53.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs = listOf("--enable-preview")
}

application {
    mainClass = "shamsasari.wallhaven.Main"
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED"
    )
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("tools.jackson.core:jackson-databind:3.0.0")
}
