package gg.seam.mod.data

import gg.seam.mod.SeamClient
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Filesystem layout + atomic JSON I/O for all Seam persistence. Everything lives under
 * `<gameDir>/seam/`. See docs/fabric-1.21.11-reference.md §7.
 *
 * All disk access is funneled through a single background thread ([io]) so it never blocks the
 * client render/tick thread and same-file writes can't interleave. Callers marshal results back
 * to the client thread with `MinecraftClient.getInstance().execute { }` when they need to touch
 * game state.
 */
object SeamStorage {
    /** `<gameDir>/seam` — the root of all mod-owned data. Created lazily on first write. */
    val root: Path by lazy { FabricLoader.getInstance().gameDir.resolve("seam") }

    /** `<gameDir>/seam/worlds` — per-world / per-server binding files. */
    val worldsDir: Path get() = root.resolve("worlds")

    val json: Json = Json {
        ignoreUnknownKeys = true    // tolerate schema drift from newer mod versions
        isLenient = true
        explicitNulls = false       // omit null fields (e.g. an unset auth block)
        coerceInputValues = true    // unknown enum values fall back to the field default, not a crash
        encodeDefaults = true       // write defaults so the file is self-documenting
        prettyPrint = true
    }

    // Single-thread executor: serializes writes to the same file and keeps I/O off the client thread.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "seam-io").apply { isDaemon = true }
    }

    /** Read + deserialize [path] on the I/O thread. Missing/corrupt file resolves to `null`. */
    fun <T> loadAsync(path: Path, deserializer: DeserializationStrategy<T>): CompletableFuture<T?> =
        CompletableFuture.supplyAsync({ readJson(path, deserializer) }, io)

    /** Serialize + atomically write [value] to [path] on the I/O thread. */
    fun <T> saveAsync(path: Path, serializer: SerializationStrategy<T>, value: T): CompletableFuture<Void> =
        CompletableFuture.runAsync({ writeJsonAtomic(path, serializer, value) }, io)

    private fun <T> readJson(path: Path, deserializer: DeserializationStrategy<T>): T? {
        if (!Files.exists(path)) return null
        return try {
            json.decodeFromString(deserializer, Files.readString(path))
        } catch (e: Exception) {
            // Preserve the unreadable file instead of letting the next save clobber it. A file
            // written by an older mod version whose schema has since changed lands here too, so
            // this is not necessarily damage — say so rather than shouting "corrupt".
            SeamClient.logger.warn(
                "Could not read Seam data at $path (${e.message}); preserved as .corrupt and " +
                    "starting from defaults. A file written by an older mod version can cause this once.",
            )
            runCatching {
                Files.move(path, path.resolveSibling("${path.fileName}.corrupt"), StandardCopyOption.REPLACE_EXISTING)
            }
            null
        }
    }

    private fun <T> writeJsonAtomic(path: Path, serializer: SerializationStrategy<T>, value: T) {
        try {
            Files.createDirectories(path.parent)
            val text = json.encodeToString(serializer, value)
            // Write to a sibling temp (same filesystem store) then move into place — no torn reads.
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            SeamClient.logger.error("Failed to write Seam data to $path", e)
        }
    }
}
