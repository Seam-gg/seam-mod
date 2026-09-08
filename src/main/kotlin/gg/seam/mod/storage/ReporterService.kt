package gg.seam.mod.storage

import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.ReportedContainerDto
import gg.seam.mod.api.ReportedItemDto
import gg.seam.mod.api.ReporterContentsRequest
import gg.seam.mod.api.SeamApiClient
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reporter loop (MCO-260 / MCO-535).
 *
 * **No `MinecraftClient` anywhere in this package** — it runs from the `main` entrypoint, which also
 * loads on dedicated servers.
 *
 * The shape, and why:
 *
 * - **Reading happens on the server thread**, a few containers per tick, because world access is not
 *   thread-safe and a sweep must never become a tick spike. The work is proportional to the number
 *   of tagged containers (tens, maybe hundreds) and reading an inventory is a few array reads, so
 *   this is nothing like a terrain sweep.
 * - **The HTTP happens off it.** Readings are snapshotted into plain data first; the network call
 *   never runs while holding the tick.
 * - **The push carries only what changed.** The reporter keeps a hash per container in memory, and
 *   names only containers whose contents differ from the last successful push. It is otherwise
 *   stateless — a restart simply re-pushes everything once, which is why there is no resync
 *   protocol and no persistence here.
 */
class ReporterService(
    private val config: ReporterConfig,
    private val api: SeamApiClient,
    private val log: Logger,
    private val now: () -> Instant = Instant::now,
) {
    /** Tags as last pulled, and what each project cares about. Replaced wholesale on each re-pull. */
    private var tags: List<TaggedContainer> = emptyList()
    private var interest: Map<Int, Set<String>> = emptyMap()

    /** One canonical container per physical inventory — see [ContainerSweep.canonicalByGroup]. */
    private var canonical: Set<Long> = emptySet()

    /** Round-robin cursor over [tags], so every container is reached without re-reading any early. */
    private var cursor = 0

    /** Contents as of the last successful push, per container, for the diff. */
    private val lastPushed = mutableMapOf<Long, Map<String, Long>>()

    /** Readings accumulated since the last push. */
    private val pending = mutableMapOf<Long, ContainerSweep.Reading>()

    private var lastTagPull: Instant? = null
    private var lastPush: Instant? = null
    private val pullInFlight = AtomicBoolean(false)
    private val pushInFlight = AtomicBoolean(false)

    /** Called every server tick, on the server thread. */
    fun tick(server: MinecraftServer) {
        val instant = now()

        if (shouldPullTags(instant)) pullTags()
        if (tags.isNotEmpty()) sweepSome(server)
        if (shouldPush(instant)) push(instant)
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    private fun shouldPullTags(instant: Instant): Boolean {
        if (pullInFlight.get()) return false
        val last = lastTagPull ?: return true
        return last.plusSeconds(TAG_PULL_SECONDS).isBefore(instant)
    }

    private fun pullTags() {
        if (!pullInFlight.compareAndSet(false, true)) return
        lastTagPull = now()
        api.getReporterTags(config.seamWorldId).whenComplete { result, thrown ->
            try {
                when {
                    thrown != null -> log.warn("Could not pull container tags: {}", thrown.javaClass.simpleName)
                    result is ApiResult.Ok -> applyTags(result.value.containers.map {
                        TaggedContainer(
                            id = it.id,
                            projectId = it.projectId,
                            dimension = it.dimension,
                            x = it.x, y = it.y, z = it.z,
                            groupKey = it.groupKey,
                        )
                    }, result.value.itemsOfInterest.associate { it.projectId to it.itemIds.toSet() })
                    result is ApiResult.Error ->
                        log.warn("Could not pull container tags: {} {}", result.status, result.code)
                    else -> log.warn("Could not pull container tags")
                }
            } finally {
                pullInFlight.set(false)
            }
        }
    }

    /**
     * Replaces the tag list. Writes plain fields only, so the server thread reading them next tick
     * sees either the old list or the new one — never a half-built one.
     */
    @Synchronized
    private fun applyTags(pulled: List<TaggedContainer>, pulledInterest: Map<Int, Set<String>>) {
        tags = pulled
        interest = pulledInterest
        canonical = ContainerSweep.canonicalByGroup(pulled).values.map { it.id }.toSet()
        if (cursor >= pulled.size) cursor = 0
        // A tag that has gone away should stop occupying the diff cache; otherwise a re-created tag
        // with the same id (there is no such thing, but a restart of the webapp is not our business)
        // could be skipped as "unchanged".
        val live = pulled.map { it.id }.toSet()
        lastPushed.keys.retainAll(live)
        pending.keys.retainAll(live)
    }

    // ── The sweep ──────────────────────────────────────────────────────────────

    /**
     * Reads up to `reads_per_tick` containers, continuing where the last tick stopped.
     *
     * With 100 tagged containers and the default 8 reads per tick, a full cycle of the cursor takes
     * about 13 ticks — well inside one sweep interval, and invisible in tick time.
     */
    private fun sweepSome(server: MinecraftServer) {
        val budget = minOf(config.readsPerTick, tags.size)
        repeat(budget) {
            val tag = tags[cursor % tags.size]
            cursor = (cursor + 1) % tags.size

            // Exactly one tag per physical inventory is read. The others are still reported — as
            // seen and empty — so they do not sit in world settings looking unreadable while
            // contributing nothing.
            val reading = if (tag.id in canonical) {
                ContainerSweep.read(server, tag, interest[tag.projectId].orEmpty())
            } else {
                ContainerSweep.Reading(tag.id, ContainerSweep.STATE_OK, emptyMap())
            }
            if (reading != null) pending[tag.id] = reading
        }
    }

    // ── The push ───────────────────────────────────────────────────────────────

    private fun shouldPush(instant: Instant): Boolean {
        if (pushInFlight.get()) return false
        val last = lastPush ?: return tags.isNotEmpty() || pending.isNotEmpty()
        return last.plusSeconds(config.sweepSeconds.toLong()).isBefore(instant)
    }

    /**
     * Pushes what changed. An empty payload still goes — that is the heartbeat, and the only thing
     * that tells world settings a server is alive but has nothing to say.
     */
    private fun push(instant: Instant) {
        if (!pushInFlight.compareAndSet(false, true)) return
        lastPush = instant

        val changed = pending.filter { (id, reading) ->
            // A state change is always worth reporting; so is any difference in the counts.
            lastPushed[id] != reading.counts || reading.state != ContainerSweep.STATE_OK
        }
        val body = ReporterContentsRequest(
            worldId = config.seamWorldId,
            sweptAt = instant.toString(),
            reporterVersion = REPORTER_VERSION,
            containers = changed.map { (id, reading) ->
                ReportedContainerDto(
                    id = id,
                    state = reading.state,
                    seenAt = instant.toString(),
                    items = reading.counts.map { ReportedItemDto(it.key, it.value) },
                )
            },
        )
        val snapshot = changed.mapValues { it.value.counts }

        api.pushReporterContents(body).whenComplete { result, thrown ->
            try {
                when {
                    thrown != null ->
                        log.warn("Could not push container contents: {}", thrown.javaClass.simpleName)
                    result is ApiResult.Ok -> {
                        // Only a confirmed write advances the diff baseline. A failed push leaves it
                        // alone, so the next one re-sends rather than silently dropping a change.
                        lastPushed.putAll(snapshot)
                        pending.clear()
                        if (result.value.rejected > 0) {
                            log.warn(
                                "The webapp rejected {} container(s) — not this world's; check seam_world_id",
                                result.value.rejected,
                            )
                        }
                    }
                    result is ApiResult.Error ->
                        log.warn("Could not push container contents: {} {}", result.status, result.code)
                    else -> log.warn("Could not push container contents")
                }
            } finally {
                pushInFlight.set(false)
            }
        }
    }

    companion object {
        /** How often the tag list and items_of_interest are re-pulled. */
        const val TAG_PULL_SECONDS = 60L

        /** Reported to the webapp so world settings can say which build is talking. */
        const val REPORTER_VERSION = "0.3.0+1.21.11"
    }
}
