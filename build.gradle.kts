plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

application {
    mainClass.set("shamsasari.wallhavenplugin.WallhavenPlugin")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED"
    )
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
}
