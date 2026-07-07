package gg.seam.mod.data

import net.minecraft.client.MinecraftClient
import net.minecraft.util.WorldSavePath
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Identity for the world/server the player is currently in — the key under which per-world data is
 * stored (`seam/worlds/<fileName>`). Singleplayer keys off the save-folder name; multiplayer keys
 * off a hash of the server address so we never write a raw host to disk. See
 * docs/fabric-1.21.11-reference.md §7.
 */
sealed interface WorldKey {
    val fileName: String
    val file: Path get() = SeamStorage.worldsDir.resolve(fileName)

    /** Singleplayer world, keyed by its save-folder name (sanitized + hash-suffixed to stay unique). */
    data class Singleplayer(val saveName: String) : WorldKey {
        override val fileName: String get() = "sp-${sanitize(saveName)}-${sha256(saveName).take(8)}.json"
    }

    /** Multiplayer server, keyed by a full SHA-256 of its `host:port` address. */
    data class Multiplayer(val address: String) : WorldKey {
        override val fileName: String get() = "mp-${sha256(address)}.json"
    }

    companion object {
        /**
         * Resolve the key for the world the [client] is currently connected to, or `null` if it
         * isn't in a world (no integrated server and no server entry).
         *
         * Accessor names verified against yarn 1.21.11+build.6: `isInSingleplayer()`, `getServer()`
         * (→ IntegratedServer), `MinecraftServer.getSavePath(WorldSavePath.ROOT)`,
         * `getCurrentServerEntry()` (→ ServerInfo) and its public `address` field.
         */
        fun forCurrent(client: MinecraftClient): WorldKey? {
            val server = client.server
            if (client.isInSingleplayer && server != null) {
                val saveName = server.getSavePath(WorldSavePath.ROOT).fileName.toString()
                return Singleplayer(saveName)
            }
            return client.currentServerEntry?.address?.let { Multiplayer(it) }
        }

        /** Keep filesystem-safe characters, collapse everything else to `_`, and cap the length. */
        private fun sanitize(raw: String): String =
            raw.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .take(32)

        private fun sha256(input: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)),
            )
    }
}
