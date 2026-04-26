plugins {
    application
    id("de.infolektuell.jextract") version "1.4.0"
    id("com.gradleup.shadow") version "9.4.1"
    id("com.github.ben-manes.versions") version "0.53.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs = listOf(
        "--enable-preview",
        "-parameters"
    )
}

application {
    mainClass = "com.hibzahim.wallcycle.Main"
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-final-field-mutation=deny",
        "-XX:+UseCompactObjectHeaders",
    )
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("tools.jackson.core:jackson-databind:3.1.1")
}

jextract {
    libraries {
        val windows by registering {
            header = file("""C:\Program Files (x86)\Windows Kits\10\Include\10.0.26100.0\um\Windows.h""")
            whitelist {
                functions.addAll("RegGetValueW", "SystemParametersInfoW")
                constants.addAll(
                    "ERROR_SUCCESS",
                    "HKEY_CURRENT_USER",
                    "RRF_RT_REG_DWORD",
                    "SPI_SETDESKWALLPAPER",
                    "SPIF_UPDATEINIFILE",
                    "SPIF_SENDCHANGE",
                )
            }
            headerClassName = "WindowsBinding"
            targetPackage = "com.hibzahim.wallcycle.os"
        }
        sourceSets.main {
            jextract.libraries.addLater(windows)
        }
    }
}
