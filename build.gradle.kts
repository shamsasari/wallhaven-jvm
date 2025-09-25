plugins {
    application
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
    mainClass = "shamsasari.wallhavenplugin.Main"
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED"
    )
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
}
