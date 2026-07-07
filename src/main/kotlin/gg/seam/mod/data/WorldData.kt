package gg.seam.mod.data

import gg.seam.mod.SeamClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture

/**
 * Per-world / per-server state, stored at `seam/worlds/<WorldKey.fileName>`. Binds this local world
 * to a Seam world ([seamWorldId]) and holds the manual resource counts the player enters in the
 * notebook. See docs/fabric-1.21.11-reference.md §7.
 *
 * [resourceCounts] is keyed project id → (item id → manual count). MVP scope (MCO-258): manual
 * counters only; container-tag maps and gather/scan caches are deferred with Phase 2.
 */
@Serializable
data class WorldData(
    val version: Int = 1,
    @SerialName("seam_world_id") val seamWorldId: String? = null,
    @SerialName("resource_counts") val resourceCounts: Map<String, Map<String, Int>> = emptyMap(),
) {
    /** Manual count for [itemId] under [projectId], or 0 if unset. */
    fun count(projectId: String, itemId: String): Int =
        resourceCounts[projectId]?.get(itemId) ?: 0

    /** Copy with [itemId]'s count under [projectId] set to [value] (removes the entry when 0). */
    fun withCount(projectId: String, itemId: String, value: Int): WorldData {
        val items = resourceCounts[projectId].orEmpty().toMutableMap()
        if (value <= 0) items.remove(itemId) else items[itemId] = value
        val counts = resourceCounts.toMutableMap()
        if (items.isEmpty()) counts.remove(projectId) else counts[projectId] = items
        return copy(resourceCounts = counts)
    }

    /** Copy with all manual counts for [projectId] cleared. */
    fun withProjectReset(projectId: String): WorldData =
        copy(resourceCounts = resourceCounts.toMutableMap().apply { remove(projectId) })
}

/**
 * In-memory holder for the current world's data, backed by its [WorldKey] file. Load on world join,
 * mutate through [update], drop on disconnect via [unload]. Disk I/O runs on [SeamStorage]'s single
 * I/O thread; [current] is published volatile for the client thread.
 */
object WorldDataStore {
    @Volatile
    var current: WorldData = WorldData()
        private set

    @Volatile
    var key: WorldKey? = null
        private set

    /** Load (or default) the data for [worldKey] and make it current. */
    fun loadAsync(worldKey: WorldKey): CompletableFuture<WorldData> {
        key = worldKey
        return SeamStorage.loadAsync(worldKey.file, WorldData.serializer()).thenApply { loaded ->
            val data = loaded ?: WorldData()
            current = data
            data
        }
    }

    /**
     * Apply [transform] to the current world data (on the calling thread), publish it, then persist
     * atomically on the I/O thread. No-op if no world is loaded (e.g. the player isn't in a world),
     * so notebook edits can't write to a stale key.
     */
    fun update(transform: (WorldData) -> WorldData): CompletableFuture<Void> {
        val worldKey = key ?: return CompletableFuture.completedFuture(null)
        val next = transform(current)
        current = next
        return SeamStorage.saveAsync(worldKey.file, WorldData.serializer(), next)
            .exceptionally { e ->
                SeamClient.logger.error("Failed to persist Seam world data for ${worldKey.fileName}", e)
                null
            }
    }

    /** Drop the loaded world (on disconnect). Resets [current] to defaults and clears [key]. */
    fun unload() {
        key = null
        current = WorldData()
    }
}
