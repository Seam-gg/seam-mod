package gg.seam.mod.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The server half's configuration — `config/seam-notebook-server.json`, read once at server start
 * (MCO-534).
 *
 * **Nothing in this package may reference `MinecraftClient`.** It is loaded by the `main`
 * entrypoint, which runs on dedicated servers where that class does not exist; a stray reference
 * fails at class-load, late and loudly. The dedicated-server load test is the guard, not eyes.
 *
 * [token] is a live credential, so [toString] redacts it: a config object is exactly the kind of
 * thing that ends up in a debug log.
 */
@Serializable
data class ReporterConfig(
    @SerialName("api_base_url") val apiBaseUrl: String = DEFAULT_BASE_URL,
    @SerialName("seam_world_id") val seamWorldId: Int = 0,
    @SerialName("token") val token: String = "",
    @SerialName("sweep_seconds") val sweepSeconds: Int = DEFAULT_SWEEP_SECONDS,
    @SerialName("reads_per_tick") val readsPerTick: Int = DEFAULT_READS_PER_TICK,
) {
    /** Whether this config names a world and carries a credential to report for it. */
    val isConfigured: Boolean
        get() = token.isNotBlank() && seamWorldId > 0

    /**
     * Clamped to something a server can survive. A `reads_per_tick` of 100000 in a hand-edited file
     * should slow a sweep down, not stall the tick loop.
     */
    fun sanitised(): ReporterConfig = copy(
        sweepSeconds = sweepSeconds.coerceIn(MIN_SWEEP_SECONDS, MAX_SWEEP_SECONDS),
        readsPerTick = readsPerTick.coerceIn(MIN_READS_PER_TICK, MAX_READS_PER_TICK),
    )

    override fun toString(): String =
        "ReporterConfig(apiBaseUrl=$apiBaseUrl, seamWorldId=$seamWorldId, token=${redacted(token)}, " +
            "sweepSeconds=$sweepSeconds, readsPerTick=$readsPerTick)"

    companion object {
        const val FILE_NAME = "seam-notebook-server.json"
        const val DEFAULT_BASE_URL = "https://app.seam.gg"
        const val DEFAULT_SWEEP_SECONDS = 30
        const val DEFAULT_READS_PER_TICK = 8

        private const val MIN_SWEEP_SECONDS = 5
        private const val MAX_SWEEP_SECONDS = 3600
        private const val MIN_READS_PER_TICK = 1
        private const val MAX_READS_PER_TICK = 256

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            // Write every field, including the ones at their defaults. This file is meant to be
            // opened and hand-tuned, and a tunable you cannot see is a tunable you do not know
            // exists — `/seam connect` would otherwise write three keys and hide sweep_seconds and
            // reads_per_tick entirely.
            encodeDefaults = true
            prettyPrint = true
        }

        /** Non-reversible placeholder that keeps the length — enough to tell empty from wrong. */
        fun redacted(secret: String?): String = when {
            secret == null -> "<redacted:null>"
            secret.isEmpty() -> "<redacted:empty>"
            else -> "<redacted:${secret.length}>"
        }

        fun path(): Path = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

        /**
         * Writes the config, creating the directory if needed. Used by `/seam connect`, so an
         * operator never has to hand-edit JSON to turn the reporter on.
         *
         * Best-effort file permissions: the file holds a live credential, so on a POSIX filesystem
         * it is made owner-only. A filesystem that does not support that (a Windows server, a
         * container with an odd mount) is not a reason to fail the write — the token is no less
         * protected than it would have been in a hand-written file.
         */
        fun save(config: ReporterConfig, path: Path = path()): Result<Unit> = runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(serializer(), config.sanitised()))
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            Unit
        }

        /**
         * Reads the config, or returns null when there is nothing to read.
         *
         * **An absent file is a supported state, not an error.** A server that installs the jar
         * without configuring it should say so once and then be quiet — never warn every tick. A
         * malformed file is different: it is someone trying and failing, so it is logged as an
         * error and still returns null rather than crashing the server.
         */
        fun load(path: Path = path(), log: Logger): ReporterConfig? {
            if (!Files.isRegularFile(path)) return null
            val text = try {
                Files.readString(path)
            } catch (e: Exception) {
                log.error("Could not read {}: {}", path, e.toString())
                return null
            }
            return try {
                json.decodeFromString(serializer(), text).sanitised()
            } catch (e: Exception) {
                // The message can quote the offending JSON, which may include the token, so only
                // the exception type is logged.
                log.error("{} is not valid JSON ({}); the reporter will not start", path, e.javaClass.simpleName)
                null
            }
        }
    }
}
