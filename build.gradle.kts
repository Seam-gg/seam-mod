plugins {
    id("fabric-loom") version "1.17.13"
    kotlin("jvm") version "2.4.0"
    // Serialization compiler plugin — the runtime lib is provided by fabric-language-kotlin.
    kotlin("plugin.serialization") version "2.4.0"
}

val modVersion: String by project
val mavenGroup: String by project
val archivesBaseName: String by project

version = modVersion
group = mavenGroup

base {
    archivesName.set(archivesBaseName)
}

repositories {
    // Loom adds Mojang + Fabric + Maven Central automatically.
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")

    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_language_kotlin_version")}")

    // kotlinx-serialization-json is provided at runtime by fabric-language-kotlin (v1.11.0).
    // Depend on it only at compile time; do NOT bundle/include it.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Tests cover the Minecraft-free half of the mod (API wire format, HTTP client, auth policy).
    // compileOnly deps don't reach the test classpath, so serialization is repeated here.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Dev-only performance mod: Sodium (GPU rendering). modLocalRuntime = present in `runClient`
    // only — NOT compiled against and NOT shipped in the mod jar. Makes the WSL2/d3d12 dev
    // client usable. Version matches the Seam server's pinned Sodium (client-mods.json).
    modLocalRuntime("maven.modrinth:sodium:mc1.21.11-0.8.12-fabric")
}

// Read from /proc rather than an env var (WSL_DISTRO_NAME): the Gradle daemon carries whatever
// environment it started with, and this decision must not depend on that.
val isWsl = runCatching {
    File("/proc/sys/kernel/osrelease").readText().contains("microsoft", ignoreCase = true)
}.getOrDefault(false)

loom {
    runs {
        named("client") {
            // Point the dev client at a local webapp by default; override per run with
            // `-PseamApiBaseUrl=https://app.seam.gg`. Set as a JVM system property rather than an
            // environment variable — the game is forked from the Gradle daemon, which carries
            // whatever environment it happened to start with. See gg.seam.mod.api.SeamApi.
            property(
                "seam.apiBaseUrl",
                project.findProperty("seamApiBaseUrl")?.toString() ?: "http://localhost:8080",
            )

            if (isWsl) {
                // Both are required for a usable WSL2 dev client (README § WSL2 dev-client
                // performance). Setting them here as well as in ~/.bashrc means they reach the
                // forked game even when the daemon was started from a shell that lacked them.
                // GALLIUM_DRIVER: Mesa otherwise picks llvmpipe (software) — sub-1 fps.
                environmentVariable("GALLIUM_DRIVER", "d3d12")
                // ALSOFT_DRIVERS: OpenAL Soft otherwise fails to open a device, then hangs the
                // shutdown watchdog destroying the dead context.
                environmentVariable("ALSOFT_DRIVERS", "pulse")
            }
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
