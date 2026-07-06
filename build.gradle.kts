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

    // Dev-only performance mod: Sodium (GPU rendering). modLocalRuntime = present in `runClient`
    // only — NOT compiled against and NOT shipped in the mod jar. Makes the WSL2/d3d12 dev
    // client usable. Version matches the Seam server's pinned Sodium (client-mods.json).
    modLocalRuntime("maven.modrinth:sodium:mc1.21.11-0.8.12-fabric")
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
