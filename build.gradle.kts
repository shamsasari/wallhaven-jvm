plugins {
    kotlin("jvm") version "1.7.0"
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

application {
    mainClass.set("shamsasari.wallhavenplugin.WallhavenPlugin")
}
