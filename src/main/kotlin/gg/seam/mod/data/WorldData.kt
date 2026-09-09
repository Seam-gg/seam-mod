package gg.seam.mod.data

import gg.seam.mod.SeamClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture

/**
 * A tag the player made that has not reached Seam yet (MCO-261).
 *
 * Keyed by position, so re-tagging the same chest three times offline collapses to the last
 * intent rather than replaying three writes. [projectId] null means **untag**, and [containerId] is
 * the webapp row to delete — absent when the tag itself never got there, in which case there is
 * nothing to delete and the queued entry is simply dropped.
 */
@Serializable
data class PendingContainerTag(
    @SerialName("dimension") val dimension: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("z") val z: Int,
    @SerialName("kind") val kind: String,
    @SerialName("group_key") val groupKey: String,
    @SerialName("project_id") val projectId: Int? = null,
    @SerialName("container_id") val containerId: Long? = null,
) {
    /** Identity in the queue: one intent per position. */
    val key: String get() = "$dimension@$x,$y,$z"
}

/**
 * Per-world / per-server state, stored at `seam/worlds/<WorldKey.fileName>`. Binds this local world
 * to a Seam world ([seamWorldId]) and holds the manual resource counts the player enters in the
 * notebook. See docs/fabric-1.21.11-reference.md §7.
 *
 * [resourceCounts] is keyed project id → (item id → manual count). Ids are `Int` because that is
 * what mc-org and its database use — the string ids this file briefly held came from the Phase 1
 * placeholder data (MCO-268). A file written by that build fails to decode and [SeamStorage]
 * preserves it as `.corrupt` and starts from defaults; only throwaway placeholder counts are lost.
 */
@Serializable
data class WorldData(
    // 4: pending_container_tags (MCO-261). Older files still decode — the field has a default —
    // so this is a marker, not a migration.
    val version: Int = 4,
    @SerialName("seam_world_id") val seamWorldId: Int? = null,
    @SerialName("resource_counts") val resourceCounts: Map<Int, Map<String, Int>> = emptyMap(),
    /** Projects whose local counts haven't reached Seam yet — the offline queue (MCO-269). */
    @SerialName("pending_resource_projects") val pendingResourceProjects: Set<Int> = emptySet(),
    /** Task toggles not yet pushed, project id → task id → desired completed state. */
    @SerialName("pending_tasks") val pendingTasks: Map<Int, Map<Int, Boolean>> = emptyMap(),
    /** Container tags not yet pushed, by [PendingContainerTag.key] (MCO-261). */
    @SerialName("pending_container_tags") val pendingContainerTags: Map<String, PendingContainerTag> = emptyMap(),
) {
    /** Local count for [itemId] under [projectId], or 0 if the player hasn't touched it. */
    fun count(projectId: Int, itemId: String): Int =
        resourceCounts[projectId]?.get(itemId) ?: 0

    /**
     * Copy with [itemId]'s count under [projectId] set to [value], marking the project unsynced.
     *
     * A zero is **stored, not pruned**: the key set is the set of items the player has touched, and
     * the sync is an absolute set. Dropping a zero would make "I emptied this" indistinguishable
     * from "I never touched this", and the push would silently leave the old server count standing.
     * Counts are cleared wholesale once they reach Seam ([withProjectReset]), so the file stays small.
     */
    fun withCount(projectId: Int, itemId: String, value: Int): WorldData {
        val items = resourceCounts[projectId].orEmpty() + (itemId to value.coerceAtLeast(0))
        return copy(
            resourceCounts = resourceCounts + (projectId to items),
            pendingResourceProjects = pendingResourceProjects + projectId,
        )
    }

    /** Copy with all local counts for [projectId] cleared, and no longer queued. */
    fun withProjectReset(projectId: Int): WorldData =
        copy(
            resourceCounts = resourceCounts - projectId,
            pendingResourceProjects = pendingResourceProjects - projectId,
        )

    /** The desired state of a queued task toggle, or null when nothing is queued for it. */
    fun pendingTask(projectId: Int, taskId: Int): Boolean? = pendingTasks[projectId]?.get(taskId)

    /** Copy with a task toggle queued for pushing. */
    fun withPendingTask(projectId: Int, taskId: Int, completed: Boolean): WorldData =
        copy(pendingTasks = pendingTasks + (projectId to (pendingTasks[projectId].orEmpty() + (taskId to completed))))

    /** Copy with a task toggle removed from the queue (it reached Seam). */
    fun withoutPendingTask(projectId: Int, taskId: Int): WorldData {
        val remaining = pendingTasks[projectId].orEmpty() - taskId
        return copy(
            pendingTasks = if (remaining.isEmpty()) pendingTasks - projectId else pendingTasks + (projectId to remaining),
        )
    }

    /** The queued intent for a position, or null when nothing is waiting for it. */
    fun pendingContainerTag(key: String): PendingContainerTag? = pendingContainerTags[key]

    /** Copy with a tag intent queued, replacing any earlier intent for the same position. */
    fun withPendingContainerTag(tag: PendingContainerTag): WorldData =
        copy(pendingContainerTags = pendingContainerTags + (tag.key to tag))

    /** Copy with a queued tag intent removed — because it reached Seam, or was superseded. */
    fun withoutPendingContainerTag(key: String): WorldData =
        copy(pendingContainerTags = pendingContainerTags - key)

    /** How many queued writes are waiting — what the notebook footer reports. */
    val queuedWrites: Int
        get() = pendingResourceProjects.size + pendingTasks.values.sumOf { it.size } + pendingContainerTags.size

    /**
     * Copy bound to Seam world [worldId]. Counts and queued writes are dropped, since project and
     * task ids are scoped to a world and would address the wrong rows under a different one.
     */
    fun withSeamWorld(worldId: Int): WorldData =
        if (worldId == seamWorldId) {
            this
        } else {
            copy(
                seamWorldId = worldId,
                resourceCounts = emptyMap(),
                pendingResourceProjects = emptySet(),
                pendingTasks = emptyMap(),
                // Container tags name a project too, so they are as world-scoped as the rest.
                pendingContainerTags = emptyMap(),
            )
        }
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
