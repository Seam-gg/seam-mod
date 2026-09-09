package gg.seam.mod.data

import gg.seam.mod.SeamClient
import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.ContainerTagRequest
import gg.seam.mod.api.OkResponse
import gg.seam.mod.api.SeamApi
import gg.seam.mod.api.SeamApiClient
import gg.seam.mod.api.SyncResourceItem
import gg.seam.mod.api.describe
import java.util.concurrent.CompletableFuture

/** A queued task toggle, identified by the project it belongs to. */
data class TaskRef(val projectId: Int, val taskId: Int)

/**
 * What [SeamSync.flush] managed to push, and why it stopped if it did.
 *
 * Returned rather than applied so the flush itself stays free of [WorldDataStore] — the caller
 * clears exactly what succeeded, and anything unmentioned stays queued for the next attempt.
 */
data class FlushResult(
    val syncedProjects: Set<Int> = emptySet(),
    val syncedTasks: List<TaskRef> = emptyList(),
    /** Keys of container-tag intents that reached Seam (MCO-261). */
    val syncedContainerTags: List<String> = emptyList(),
    /** Keys the server refused permanently. De-queued like a success, but not one. */
    val rejectedContainerTags: List<String> = emptyList(),
    val failure: String? = null,
) {
    val pushed: Int
        get() = syncedProjects.size + syncedTasks.size + syncedContainerTags.size + rejectedContainerTags.size
}

/** Where the last push got to. The notebook footer renders this next to the queued-writes count. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Synced(val atMillis: Long) : SyncState
    data class Failed(val reason: String) : SyncState
}

/**
 * Pushes local edits to Seam (MCO-269).
 *
 * Everything the player changes in-game is written to [WorldDataStore] first and queued there, so
 * an edit made on a plane survives the flight, the game closing, and the reconnect. [flush] drains
 * that queue; whatever fails stays queued.
 *
 * Resource pushes are an **absolute set** of the items the player has touched — never a delta — so
 * replaying a queue that partly succeeded can't double-count. Items the player never touched are
 * not in the payload at all, so the web app's values for them stand.
 */
object SeamSync {

    @Volatile
    var state: SyncState = SyncState.Idle
        private set

    /** One flush at a time. The retry timer and a fresh tag can otherwise overlap and double-push. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Push everything queued, then clear what succeeded.
     *
     * Sequential, not parallel: the queue is small, and a burst of writes from every client on a
     * shared server is exactly the load pattern the API doesn't need. Stops at the first failure —
     * if the server is unreachable for one call it's unreachable for the rest, and the remaining
     * items stay queued rather than each producing its own error.
     */
    fun flush(
        client: SeamApiClient = SeamApi.client,
        data: WorldData = WorldDataStore.current,
        commit: (FlushResult) -> Unit = ::applyToStore,
        now: () -> Long = System::currentTimeMillis,
    ): CompletableFuture<FlushResult> {
        if (data.queuedWrites == 0) return CompletableFuture.completedFuture(FlushResult())
        if (!inFlight.compareAndSet(false, true)) return CompletableFuture.completedFuture(FlushResult())

        state = SyncState.Syncing
        var chain = CompletableFuture.completedFuture(FlushResult())

        for (projectId in data.pendingResourceProjects.sorted()) {
            chain = chain.thenCompose { acc ->
                if (acc.failure != null) return@thenCompose CompletableFuture.completedFuture(acc)
                val items = data.resourceCounts[projectId].orEmpty()
                    .map { (itemId, collected) -> SyncResourceItem(itemId, collected) }
                client.syncResources(projectId, items).thenApply { result ->
                    when (result) {
                        is ApiResult.Ok -> acc.copy(syncedProjects = acc.syncedProjects + projectId)
                        else -> acc.copy(failure = result.describe())
                    }
                }
            }
        }

        for ((projectId, toggles) in data.pendingTasks) {
            for ((taskId, completed) in toggles) {
                chain = chain.thenCompose { acc ->
                    if (acc.failure != null) return@thenCompose CompletableFuture.completedFuture(acc)
                    client.updateTask(projectId, taskId, completed).thenApply { result ->
                        when (result) {
                            is ApiResult.Ok -> acc.copy(syncedTasks = acc.syncedTasks + TaskRef(projectId, taskId))
                            // A task deleted in the web app 404s. Retrying forever would wedge the
                            // queue, so treat it as done and drop it.
                            is ApiResult.Error ->
                                if (result.status == 404) acc.copy(syncedTasks = acc.syncedTasks + TaskRef(projectId, taskId))
                                else acc.copy(failure = result.describe())
                            is ApiResult.Failure -> acc.copy(failure = result.describe())
                        }
                    }
                }
            }
        }

        // Container tags (MCO-261). Ordered after the rest for no reason beyond determinism; they
        // share the queue but nothing else touches the same rows.
        val worldId = data.seamWorldId
        for (pending in data.pendingContainerTags.values.sortedBy { it.key }) {
            chain = chain.thenCompose { acc ->
                if (acc.failure != null) return@thenCompose CompletableFuture.completedFuture(acc)
                if (worldId == null) {
                    // Nothing to address the write to. Dropping beats retrying forever: the world
                    // binding is gone, so this tag names a project id that means nothing now.
                    return@thenCompose CompletableFuture.completedFuture(
                        acc.copy(syncedContainerTags = acc.syncedContainerTags + pending.key),
                    )
                }
                pushContainerTag(client, worldId, pending).thenApply { result ->
                    when (result) {
                        is ApiResult.Ok -> acc.copy(syncedContainerTags = acc.syncedContainerTags + pending.key)
                        is ApiResult.Error ->
                            if (isPermanent(result.status)) {
                                // The server will never accept this one, so retrying it does not
                                // eventually succeed — it wedges the queue forever, and every
                                // resource count and task toggle behind it with it, because the
                                // chain stops at the first failure. Drop it and say so out loud.
                                SeamClient.logger.warn(
                                    "Seam rejected a container tag at {},{},{} and it has been dropped: {}",
                                    pending.x, pending.y, pending.z, result.describe(),
                                )
                                acc.copy(rejectedContainerTags = acc.rejectedContainerTags + pending.key)
                            } else {
                                acc.copy(failure = result.describe())
                            }
                        is ApiResult.Failure -> acc.copy(failure = result.describe())
                    }
                }
            }
        }

        return chain.thenApply { result ->
            commit(result)
            // A queued tag reaching Seam was completely silent: `ContainerTagStore` logs when you
            // MAKE a tag and this class logs when one is REJECTED, so the successful case — the
            // one the offline queue exists to produce — left no trace at all. Silent success reads
            // exactly like a lost tag, and there is no way to tell them apart from inside the game.
            if (result.syncedContainerTags.isNotEmpty()) {
                SeamClient.logger.info(
                    "Sent {} queued container tag(s) to Seam",
                    result.syncedContainerTags.size,
                )
            }
            state = if (result.failure != null) SyncState.Failed(result.failure) else SyncState.Synced(now())
            result
        }.whenComplete { _, _ -> inFlight.set(false) }
    }

    /**
     * Whether [status] means "never going to work", so the intent should be dropped rather than
     * retried forever.
     *
     * A **401 is deliberately retryable**: the token expired, the player re-links, and the queue
     * then flushes — dropping their tags on the way would be the wrong answer to a fixable problem.
     * So are 408 and 429, which are explicitly "try later". Everything else in the 4xx range is the
     * server saying this request is wrong, and it will still be wrong next time — a 404 because the
     * container was untagged in the web app (the intent is already satisfied), a 400 or 403 because
     * the request or the caller is not acceptable and no amount of waiting changes that.
     */
    private fun isPermanent(status: Int): Boolean =
        status in 400..499 && status != 401 && status != 408 && status != 429

    /**
     * One queued intent, as the API call it stands for.
     *
     * An untag with no [PendingContainerTag.containerId] is a tag that never reached Seam in the
     * first place — there is no row to delete, so the intent is satisfied by dropping it.
     */
    private fun pushContainerTag(
        client: SeamApiClient,
        worldId: Int,
        pending: PendingContainerTag,
    ): CompletableFuture<out ApiResult<*>> = when {
        pending.projectId != null -> client.tagContainer(
            worldId,
            ContainerTagRequest(
                dimension = pending.dimension,
                x = pending.x, y = pending.y, z = pending.z,
                projectId = pending.projectId,
                kind = pending.kind,
                groupKey = pending.groupKey,
            ),
        )
        pending.containerId != null -> client.untagContainer(worldId, pending.containerId)
        else -> CompletableFuture.completedFuture(ApiResult.Ok(OkResponse()))
    }

    /**
     * Clear the successfully pushed entries. Counts are dropped entirely, not just de-queued: Seam
     * now holds the same values, so the notebook can fall back to reading them from the server and
     * a later edit made in the web app won't be shadowed by a stale local copy.
     */
    private fun applyToStore(result: FlushResult) {
        if (result.pushed == 0) return
        WorldDataStore.update { data ->
            var next = data
            result.syncedProjects.forEach { next = next.withProjectReset(it) }
            result.syncedTasks.forEach { next = next.withoutPendingTask(it.projectId, it.taskId) }
            result.syncedContainerTags.forEach { next = next.withoutPendingContainerTag(it) }
            result.rejectedContainerTags.forEach { next = next.withoutPendingContainerTag(it) }
            next
        }
    }

    /** Reset on disconnect — the queue lives on disk per world, this is only the last-push status. */
    fun clear() {
        state = SyncState.Idle
    }
}
