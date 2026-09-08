package gg.seam.mod.tag

import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.ContainerTagDto
import gg.seam.mod.api.SeamApi
import gg.seam.mod.api.SeamApiClient
import gg.seam.mod.api.describe
import gg.seam.mod.data.PendingContainerTag
import gg.seam.mod.data.SeamSync
import gg.seam.mod.data.WorldData
import gg.seam.mod.data.WorldDataStore
import java.util.concurrent.CompletableFuture

/**
 * What this world's containers are tagged as, and how tagging one happens (MCO-261).
 *
 * **A tag is written to the offline queue first and pushed second.** Not because the network is
 * expected to fail, but because the alternative loses work in the ordinary case: the player tags a
 * chest, the request is in flight, and the game closes. Queue-first makes that survivable, and it
 * costs nothing — [SeamSync.flush] runs immediately after, so an online tag still lands in the same
 * moment it would have. It also means there is exactly one code path to the API for tags, shared
 * with the reconnect flush, rather than two that can drift.
 */
object ContainerTagStore {

    /** What a container is assigned to, as far as the client can tell. */
    sealed interface Assignment {
        /** Seam has this, and these are its rows. */
        data class Synced(val projectId: Int, val tagIds: List<Long>) : Assignment

        /** The player asked for this and it has not reached Seam yet. [projectId] null = untag. */
        data class Queued(val projectId: Int?) : Assignment
    }

    /** Tags as of the last successful pull. Empty before one, which is not the same as "none". */
    @Volatile
    var tags: List<ContainerTagDto> = emptyList()
        private set

    /** True once a pull has succeeded, so the picker can tell "no tags" from "don't know yet". */
    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var fetchedAtMillis = 0L

    /** Pull this world's tags. The picker needs them to tell untagged from already-assigned. */
    fun refresh(
        client: SeamApiClient = SeamApi.client,
        seamWorldId: Int? = WorldDataStore.current.seamWorldId,
        now: () -> Long = System::currentTimeMillis,
    ): CompletableFuture<Unit> {
        val worldId = seamWorldId ?: return CompletableFuture.completedFuture(Unit)
        return client.getContainerTags(worldId).thenApply { result ->
            when (result) {
                is ApiResult.Ok -> {
                    tags = result.value.containers
                    loaded = true
                    lastError = null
                    fetchedAtMillis = now()
                }
                is ApiResult.Error -> lastError = "Could not load tags: ${result.describe()}"
                is ApiResult.Failure -> lastError = "Could not reach Seam: ${result.describe()}"
            }
            Unit
        }
    }

    /**
     * Refresh unless the cache is fresh. The picker opens from a gesture that can be repeated
     * quickly, and `Screen.init()` re-runs on every window resize, so an unguarded pull would
     * hammer the API for no new information.
     */
    fun refreshIfStale(maxAgeMillis: Long = MAX_AGE_MILLIS, now: () -> Long = System::currentTimeMillis) {
        if (loaded && now() - fetchedAtMillis < maxAgeMillis) return
        refresh(now = now)
    }

    /**
     * What [target] is assigned to right now — the queue first, because a queued intent is newer
     * than anything the last pull could know, and the picker must show what the player just did
     * rather than what the server last said.
     */
    fun assignmentFor(
        target: ContainerTarget,
        data: WorldData = WorldDataStore.current,
        knownTags: List<ContainerTagDto> = tags,
    ): Assignment? {
        val keys = target.positions.map { pendingKey(target.dimension, it) }
        keys.firstNotNullOfOrNull { data.pendingContainerTag(it) }?.let {
            return Assignment.Queued(it.projectId)
        }

        val rows = knownTags.filter { row ->
            row.dimension == target.dimension && target.positions.any { it.x == row.x && it.y == row.y && it.z == row.z }
        }
        val projectId = rows.firstOrNull()?.projectId ?: return null
        return Assignment.Synced(projectId, rows.map { it.id })
    }

    /**
     * Tag every row of [target] to [projectId], replacing whatever was there.
     *
     * Re-tagging is a plain overwrite with no confirmation: the picker already shows the current
     * assignment, moving a chest between projects is cheap and reversible, and once in-world labels
     * land (MCO-264) the assignment is visible without opening anything at all.
     */
    fun tag(target: ContainerTarget, projectId: Int): CompletableFuture<Unit> {
        WorldDataStore.update { data ->
            var next = data
            for (pos in target.positions) {
                next = next.withPendingContainerTag(
                    PendingContainerTag(
                        dimension = target.dimension,
                        x = pos.x, y = pos.y, z = pos.z,
                        kind = target.kind,
                        groupKey = target.groupKey,
                        projectId = projectId,
                    ),
                )
            }
            next
        }
        return push()
    }

    /**
     * Untag every row of [target].
     *
     * A position whose tag never reached Seam has nothing to delete, so its queued intent is simply
     * dropped — queueing an untag for a row that does not exist would send a `DELETE` for an id we
     * do not have.
     */
    fun untag(target: ContainerTarget): CompletableFuture<Unit> {
        val idByPosition = tags.filter { row -> row.dimension == target.dimension }
            .associateBy { pendingKey(target.dimension, TagPos(it.x, it.y, it.z)) }
        var anythingToSend = false

        WorldDataStore.update { data ->
            var next = data
            for (pos in target.positions) {
                val key = pendingKey(target.dimension, pos)
                val containerId = idByPosition[key]?.id
                when {
                    // Seam has a row for this position, so there is something to delete.
                    containerId != null -> {
                        anythingToSend = true
                        next = next.withPendingContainerTag(
                            PendingContainerTag(
                                dimension = target.dimension,
                                x = pos.x, y = pos.y, z = pos.z,
                                kind = target.kind,
                                groupKey = target.groupKey,
                                projectId = null,
                                containerId = containerId,
                            ),
                        )
                    }
                    // Queued but never created: cancelling the create *is* the untag, and there is
                    // no id to send a DELETE for.
                    next.pendingContainerTag(key) != null -> next = next.withoutPendingContainerTag(key)
                }
            }
            next
        }

        // Untagging something Seam never had is purely local. Flushing anyway would push every
        // other queued write as a side effect of a button that, here, changed nothing remote.
        if (!anythingToSend) return CompletableFuture.completedFuture(Unit)
        return push()
    }

    /** Flush the queue, then re-read, so the picker reflects what Seam actually holds. */
    private fun push(): CompletableFuture<Unit> =
        SeamSync.flush().thenCompose { refresh() }

    /** Drop cached tags on disconnect — a second world must never render the first one's. */
    fun clear() {
        tags = emptyList()
        loaded = false
        lastError = null
        fetchedAtMillis = 0L
    }

    private fun pendingKey(dimension: String, pos: TagPos): String = "$dimension@$pos"

    /** Long enough to survive a window resize, short enough that reopening shows the truth. */
    private const val MAX_AGE_MILLIS = 10_000L
}
