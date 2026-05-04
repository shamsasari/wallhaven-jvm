plugins {
    application
    id("de.infolektuell.jextract") version "1.4.0"
    id("com.gradleup.shadow") version "9.4.1"
    id("org.beryx.jlink") version "4.0.0"
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
        "-parameters",
    )
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

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.25.4")
    implementation("tools.jackson.core:jackson-databind:3.1.1")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.18.0")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainModule = "com.hibzahim.wallcycle"
    mainClass = "com.hibzahim.wallcycle.Main"
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=${mainModule.get()}",
        "--illegal-final-field-mutation=deny",
        "-XX:+UseCompactObjectHeaders",
    )
}

jlink {
    launcher {
        noConsole = true
    }
}
