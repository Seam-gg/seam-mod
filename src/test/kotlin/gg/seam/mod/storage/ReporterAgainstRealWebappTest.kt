package gg.seam.mod.storage

import gg.seam.mod.api.SeamApiClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reporter against a **real mc-org**, with a real reporter token.
 *
 * Everything else in this package proves the reporter behaves against a webapp this repo wrote.
 * That is worth a lot and catches nothing about the actual contract: whether mc-org's
 * `GET /reporter/tags` decodes into these wire models, whether a reporter token is accepted where
 * it should be, whether `POST /reporter/contents` takes a body shaped the way this mod builds one.
 * Those only fail against the other repo.
 *
 * **Skipped unless pointed at one.** It needs a running webapp and a reporter token, so CI skips
 * it — which means it does not replace the other tests, it de-risks the first live run:
 *
 *     cd mc-org/webapp && ./scripts/start-db.sh && ./scripts/migrate-locally.sh
 *     ./scripts/run.sh --env local
 *     # mint a token: world settings → Connected server → Generate token
 *     SEAM_SMOKE_BASE_URL=http://localhost:8080 \
 *     SEAM_SMOKE_TOKEN=<the token> \
 *     SEAM_SMOKE_WORLD_ID=1 \
 *     ./gradlew test --tests '*ReporterAgainstRealWebappTest*'
 *
 * The world does not have to exist in Minecraft — the block reads are faked, deliberately. What is
 * real is the token, the HTTP, the JSON and mc-org's own validation of it.
 */
class ReporterAgainstRealWebappTest {

    private val baseUrl = System.getenv("SEAM_SMOKE_BASE_URL")
    private val token = System.getenv("SEAM_SMOKE_TOKEN")
    private val worldId = System.getenv("SEAM_SMOKE_WORLD_ID")?.toIntOrNull() ?: 1

    private val log = LoggerFactory.getLogger(ReporterAgainstRealWebappTest::class.java)
    private var clock: Instant = Instant.now()

    /** Reports a plausible stock of whatever the project actually asked for. */
    private val readsSeen = mutableListOf<List<Long>>()
    private val reader = ReporterService.GroupReader { group, wanted ->
        readsSeen += group.map { it.id }
        val holder = group.first()
        val counts = wanted.take(2).associateWith { 12L }
        group.associate { m ->
            m.id to ContainerSweep.Reading(
                m.id,
                ContainerSweep.STATE_OK,
                if (m.id == holder.id) counts else emptyMap(),
            )
        }
    }

    private fun service(): ReporterService {
        val config = ReporterConfig(apiBaseUrl = baseUrl!!, seamWorldId = worldId, token = token!!)
        return ReporterService(
            config = config,
            api = SeamApiClient(baseUrl = { config.apiBaseUrl }, token = { config.token }),
            log = log,
            now = { clock },
        )
    }

    @Test
    fun `a real reporter token pulls real tags and its push is accepted`() {
        assumeTrue(
            !baseUrl.isNullOrBlank() && !token.isNullOrBlank(),
            "set SEAM_SMOKE_BASE_URL and SEAM_SMOKE_TOKEN to run this",
        )

        val service = service()

        // The boot heartbeat. This alone proves the token is accepted and the world is resolved —
        // a wrong token or a token for another world fails here, not thirty seconds later.
        tickUntil(service, "the boot heartbeat to be accepted") { service.status().lastPushAt != null }
        assertNull(service.status().lastError, "the heartbeat was refused")

        // Then the tag list, and a pass over it.
        tickUntil(service, "tags to arrive") { service.status().taggedContainers > 0 }
        assertTrue(
            readsSeen.isNotEmpty(),
            "tags arrived but no pass read them — the sweep is not being started",
        )

        tickUntil(service, "a pass to finish") { service.status().lastPassEndedAt != null }
        tickUntil(service, "the contents push to be accepted") { service.status().lastPushContainers > 0 }

        val reported = service.status()
        assertNull(reported.lastError, "mc-org rejected the push")
        assertNotNull(reported.lastPushAt)
        log.info("Smoke: mc-org accepted {}", reported)

        // And then the diff, against the real thing: nothing has changed in the fake world, so the
        // next pass must find nothing to say. A reporter that re-sends its whole state every
        // interval would pass every other test in this package and quietly hammer production.
        val before = reported.lastPushAt
        clock = clock.plus(Duration.ofSeconds(31))
        tickUntil(service, "a second accepted push") { service.status().lastPushAt != before }
        assertEquals(0, service.status().lastPushContainers, "unchanged containers were re-sent")
    }

    private fun tickUntil(service: ReporterService, what: String, condition: () -> Boolean) {
        repeat(600) {
            if (condition()) return
            service.tick(reader)
            Thread.sleep(10)
        }
        if (!condition()) throw AssertionError("timed out waiting for $what — ${service.status()}")
    }
}
