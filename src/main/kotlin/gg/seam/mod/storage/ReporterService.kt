package gg.seam.mod.storage

import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.ReportedContainerDto
import gg.seam.mod.api.ReportedItemDto
import gg.seam.mod.api.ReporterContentsRequest
import gg.seam.mod.api.SeamApiClient
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The reporter loop (MCO-260 / MCO-535).
 *
 * **No `MinecraftClient` anywhere in this package** — it runs from the `main` entrypoint, which also
 * loads on dedicated servers.
 *
 * The shape, and why:
 *
 * - **One pass per `sweep_seconds`.** A pass reads every tagged group exactly once, a few per tick,
 *   and the push happens when the pass ends. Reading continuously between pushes would burn ticks
 *   producing readings nobody sends; tying the push to the end of a pass also means a push carries
 *   one coherent look at the base rather than a mix of readings minutes apart.
 * - **Reading happens on the server thread**, because world access is not thread-safe and a sweep
 *   must never become a tick spike. The work is proportional to the number of tagged containers
 *   (tens, maybe hundreds) and reading an inventory is a few array reads.
 * - **The HTTP happens off it, and its results come back through [inbox].** Every field below is
 *   owned by the server thread and touched from nowhere else. An HTTP callback fires on the
 *   client's own executor, so it may not write them directly — it queues a change that the next
 *   tick applies. Before this, two threads shared plain `HashMap`s, which is not a subtle race:
 *   a concurrent resize corrupts the map or spins.
 * - **The push carries only what changed** against the last *confirmed* push, so a failure re-sends
 *   rather than silently dropping a change. It is otherwise stateless — a restart re-pushes
 *   everything once, which is why there is no resync protocol and nothing persisted here.
 */
class ReporterService(
    private val config: ReporterConfig,
    private val api: SeamApiClient,
    private val log: Logger,
    private val now: () -> Instant = Instant::now,
) {
    /** What `/seam status` shows. Read on the server thread, where commands also run. */
    data class Status(
        val taggedContainers: Int,
        val groups: Int,
        val lastPassEndedAt: Instant?,
        val lastPushAt: Instant?,
        val lastPushContainers: Int,
        val lastError: String?,
        val projectsWithoutItemsOfInterest: List<Int>,
    )

    // ── Server-thread state. Nothing here may be written from an HTTP callback. ─────────

    private var tags: List<TaggedContainer> = emptyList()
    private var interest: Map<Int, Set<String>> = emptyMap()

    /** Tags split into physical inventories — see [ContainerSweep.groupsOf]. A pass walks these. */
    private var groups: List<List<TaggedContainer>> = emptyList()

    /** How far into [groups] the current pass has got. */
    private var groupCursor = 0
    private var passActive = false
    private var passStartedAt: Instant? = null
    private var pushDue = false

    /** The latest reading per container, and the latest **confirmed** one. Their difference is the push. */
    private val pending = mutableMapOf<Long, ContainerSweep.Reading>()
    private val lastPushed = mutableMapOf<Long, ContainerSweep.Reading>()

    private var lastTagPull: Instant? = null
    private var lastPassEndedAt: Instant? = null
    private var lastPushAt: Instant? = null
    private var lastPushContainers = 0
    private var lastError: String? = null
    private var pullInFlight = false
    private var pushInFlight = false
    private var projectsWithoutInterest: List<Int> = emptyList()

    /** Work handed back from HTTP callback threads, applied on the server thread at the tick's top. */
    private val inbox = ConcurrentLinkedQueue<Runnable>()

    /** How one group of tags is read. The seam that lets the loop be tested without a world. */
    fun interface GroupReader {
        fun read(group: List<TaggedContainer>, itemsOfInterest: Set<String>): Map<Long, ContainerSweep.Reading>
    }

    /** Called every server tick, on the server thread. */
    fun tick(server: MinecraftServer) =
        tick { group, wanted -> ContainerSweep.readGroup(server, group, wanted) }

    /**
     * The loop itself, with world access injected.
     *
     * A `MinecraftServer` cannot be constructed in a unit test, and the scheduling — one pass per
     * interval, the diff against the last *confirmed* push, the retry after a failure — is where
     * the bugs live, not in the block reads. So the reads come in through [GroupReader] and
     * `ReporterServiceTest` drives this against a loopback webapp.
     */
    internal fun tick(read: GroupReader) {
        while (true) (inbox.poll() ?: break).run()

        val instant = now()
        if (shouldPullTags(instant)) pullTags(instant)
        if (!passActive && shouldStartPass(instant)) startPass(instant)
        if (passActive) advancePass(read)
        if (pushDue && !pushInFlight) push(now())
    }

    fun status(): Status = Status(
        taggedContainers = tags.size,
        groups = groups.size,
        lastPassEndedAt = lastPassEndedAt,
        lastPushAt = lastPushAt,
        lastPushContainers = lastPushContainers,
        lastError = lastError,
        projectsWithoutItemsOfInterest = projectsWithoutInterest,
    )

    // ── Tags ───────────────────────────────────────────────────────────────────

    private fun shouldPullTags(instant: Instant): Boolean {
        if (pullInFlight) return false
        val last = lastTagPull ?: return true
        return last.plusSeconds(TAG_PULL_SECONDS).isBefore(instant)
    }

    private fun pullTags(instant: Instant) {
        pullInFlight = true
        lastTagPull = instant
        api.getReporterTags(config.seamWorldId).whenComplete { result, thrown ->
            inbox += Runnable {
                pullInFlight = false
                when {
                    thrown != null -> fail("Could not pull container tags", thrown.javaClass.simpleName)
                    result is ApiResult.Ok -> applyTags(
                        result.value.containers.map {
                            TaggedContainer(
                                id = it.id,
                                projectId = it.projectId,
                                dimension = it.dimension,
                                x = it.x, y = it.y, z = it.z,
                                groupKey = it.groupKey,
                            )
                        },
                        result.value.itemsOfInterest.associate { it.projectId to it.itemIds.toSet() },
                    )
                    result is ApiResult.Error ->
                        fail("Could not pull container tags", "${result.status} ${result.code}")
                    else -> fail("Could not pull container tags", "unknown")
                }
            }
        }
    }

    /** Applied on the server thread, from [inbox]. */
    private fun applyTags(pulled: List<TaggedContainer>, pulledInterest: Map<Int, Set<String>>) {
        // A changed tag list starts a pass on the next tick rather than waiting out the interval.
        // Without this the very first sweep is a whole `sweep_seconds` after boot — the tag list
        // arrives *after* the boot pass has already run against an empty one — so someone who has
        // just tagged a chest watches an empty world settings page and concludes it does not work.
        val changed = pulled != tags
        if (changed) {
            passStartedAt = null
            // The one log line that answers "did it see the chest I just tagged?". It fires only
            // when the list actually changes, so a steady base is silent — but a reporter that
            // says nothing at all is indistinguishable from a broken one, and that is exactly how
            // it feels from the far side of a webapp you are also still testing.
            log.info("Now sweeping {} tagged container(s) for world {}", pulled.size, config.seamWorldId)
        }

        tags = pulled
        interest = pulledInterest
        groups = ContainerSweep.groupsOf(pulled)
        if (groupCursor > groups.size) groupCursor = groups.size

        // A tag that has gone away must stop occupying the diff, or a later tag reusing its id
        // would be skipped as "unchanged".
        val live = pulled.map { it.id }.toSet()
        lastPushed.keys.retainAll(live)
        pending.keys.retainAll(live)

        // A tagged project with nothing to measure reports nothing, by design — but from the
        // outside that is indistinguishable from a broken sweep, so it is worth one line. Only
        // when the set changes: this runs every minute.
        val silent = pulled.map { it.projectId }.distinct().filter { interest[it].isNullOrEmpty() }.sorted()
        if (silent != projectsWithoutInterest) {
            projectsWithoutInterest = silent
            if (silent.isNotEmpty()) {
                log.info(
                    "Tagged containers belong to project(s) {} which have no target or plan items — " +
                        "nothing to measure there until something is added to the project.",
                    silent.joinToString(", "),
                )
            }
        }
    }

    // ── The sweep ──────────────────────────────────────────────────────────────

    private fun shouldStartPass(instant: Instant): Boolean {
        val last = passStartedAt ?: return true
        return last.plusSeconds(config.sweepSeconds.toLong()).isBefore(instant)
    }

    private fun startPass(instant: Instant) {
        passStartedAt = instant
        groupCursor = 0
        passActive = true
    }

    /**
     * Reads up to `reads_per_tick` containers of the current pass, continuing where the last tick
     * stopped, and ends the pass once every group has been visited.
     *
     * With 100 tagged containers and the default 8 reads per tick a pass takes about 13 ticks —
     * under a second, and invisible in tick time. Then nothing until the next interval.
     */
    private fun advancePass(read: GroupReader) {
        var budget = config.readsPerTick
        while (budget > 0 && groupCursor < groups.size) {
            val group = groups[groupCursor++]
            // The union of the members' interests: a group is one inventory, and whichever member
            // ends up holding the counts, every member's project should get what it asked for.
            val wanted = group.flatMapTo(mutableSetOf()) { interest[it.projectId].orEmpty() }
            read.read(group, wanted).forEach { (id, reading) -> pending[id] = reading }
            budget -= group.size
        }
        if (groupCursor >= groups.size) {
            passActive = false
            lastPassEndedAt = now()
            pushDue = true
        }
    }

    // ── The push ───────────────────────────────────────────────────────────────

    /**
     * Pushes what changed since the last confirmed push. An empty payload still goes — that is the
     * heartbeat, and the only thing that tells world settings a server is alive but has nothing to
     * say. The first one leaves at boot, so a bad token is a log line within a tick rather than a
     * sweep interval later.
     */
    private fun push(instant: Instant) {
        pushInFlight = true
        pushDue = false

        val changed = pending.filterTo(mutableMapOf()) { (id, reading) -> lastPushed[id] != reading }
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

        api.pushReporterContents(body).whenComplete { result, thrown ->
            inbox += Runnable {
                pushInFlight = false
                when {
                    thrown != null -> fail("Could not push container contents", thrown.javaClass.simpleName)
                    result is ApiResult.Ok -> {
                        // Only a confirmed write advances the baseline. A failed push leaves it
                        // alone, so the next one re-sends rather than dropping a change.
                        // "The webapp is accepting what we send" is worth saying exactly once —
                        // at the first accepted push, and again after recovering from a failure.
                        if (lastPushAt == null) {
                            log.info("Seam reporter: the webapp accepted its first push. Connection is working.")
                        } else if (lastError != null) {
                            log.info("Seam reporter: pushing again after an error.")
                        }
                        lastPushed.putAll(changed)
                        lastPushAt = instant
                        lastPushContainers = changed.size
                        lastError = null
                        if (result.value.rejected > 0) {
                            fail(
                                "The webapp rejected ${result.value.rejected} container(s)",
                                "not this world's — check seam_world_id",
                            )
                        }
                    }
                    result is ApiResult.Error ->
                        fail("Could not push container contents", "${result.status} ${result.code}")
                    else -> fail("Could not push container contents", "unknown")
                }
            }
        }
    }

    /** One place for "it did not work", so `/seam status` can show the same thing the log said. */
    private fun fail(what: String, detail: String) {
        lastError = "$what: $detail"
        log.warn("{}: {}", what, detail)
    }

    companion object {
        /** How often the tag list and items_of_interest are re-pulled. */
        const val TAG_PULL_SECONDS = 60L

        /**
         * Reported to the webapp so world settings can say which build is talking. Pinned to
         * `gradle.properties`' `modVersion` by `ReporterServiceTest`, so it cannot drift.
         */
        const val REPORTER_VERSION = "0.3.0+1.21.11"
    }
}
