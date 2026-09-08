package gg.seam.mod.storage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.seam.mod.api.SeamApiClient
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reporter loop (MCO-260 / MCO-535), driven against a **real loopback webapp** — the JDK's own
 * HTTP server, no test dependency — so the pulls and pushes go over the wire through the real
 * [SeamApiClient] and the real wire models. What is faked is the world: a `MinecraftServer` cannot
 * be constructed here, and the block reads are not where the bugs are.
 *
 * The bugs are here: whether a pass happens once per interval or every tick, whether a failed push
 * is retried or silently dropped, and whether a container that stops being readable keeps its count
 * or double-counts into another row.
 *
 * ## Ticking
 *
 * The loop hands its HTTP results back through an inbox that the *next* tick drains, so a test
 * cannot assert straight after a tick — it has to tick until the reply lands. [tickUntil] does
 * that, and its use is not incidental: it is the same property the server thread relies on.
 */
class ReporterServiceTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** Requests the fake webapp received, newest last. */
    private val pushes = ConcurrentLinkedQueue<String>()
    private val tagPulls = ConcurrentLinkedQueue<String>()

    @Volatile private var tagsBody = """{"world_id":1,"containers":[],"items_of_interest":[]}"""
    @Volatile private var pushStatus = 200
    @Volatile private var pushBody = """{"accepted":0,"rejected":0,"projects_recomputed":0}"""

    private val log = LoggerFactory.getLogger(ReporterServiceTest::class.java)
    private var clock: Instant = Instant.parse("2026-09-08T12:00:00Z")

    /** Every read the fake world serves, by tag id, plus a record of which groups were read. */
    private val readings = mutableMapOf<Long, ContainerSweep.Reading>()
    private val groupsRead = mutableListOf<List<Long>>()
    private val interestSeen = mutableListOf<Set<String>>()

    private val reader = ReporterService.GroupReader { group, wanted ->
        groupsRead += group.map { it.id }
        interestSeen += wanted
        group.mapNotNull { m -> readings[m.id]?.let { m.id to it } }.toMap()
    }

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.executor = null
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() = server.stop(0)

    private fun respond(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val (status, response) = when {
            path.endsWith("/reporter/tags") -> {
                tagPulls += exchange.requestURI.toString()
                200 to tagsBody
            }
            else -> {
                pushes += body
                pushStatus to pushBody
            }
        }
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun service(sweepSeconds: Int = 30, readsPerTick: Int = 8): ReporterService =
        ReporterService(
            config = ReporterConfig(
                apiBaseUrl = baseUrl,
                seamWorldId = 1,
                token = "reporter-token",
                sweepSeconds = sweepSeconds,
                readsPerTick = readsPerTick,
            ),
            api = SeamApiClient(baseUrl = { baseUrl }, token = { "reporter-token" }),
            log = log,
            now = { clock },
        )

    /** Ticks until [condition] holds, or fails. The HTTP is real, so the reply takes a moment. */
    private fun ReporterService.tickUntil(what: String, condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            tick(reader)
            Thread.sleep(5)
        }
        if (!condition()) throw AssertionError("timed out waiting for $what")
    }

    private fun tag(id: Long, projectId: Int = 7, groupKey: String = "$id,64,0") =
        TaggedContainer(id, projectId, "minecraft:overworld", id.toInt(), 64, 0, groupKey)

    private fun tagsResponse(vararg tags: TaggedContainer, items: List<String> = listOf("minecraft:iron_ingot")) =
        """
        {"world_id":1,
         "containers":[${tags.joinToString(",") {
            """{"id":${it.id},"project_id":${it.projectId},"dimension":"${it.dimension}",
                "x":${it.x},"y":${it.y},"z":${it.z},"group_key":"${it.groupKey}","kind":"chest"}"""
        }}],
         "items_of_interest":[${tags.map { it.projectId }.distinct().joinToString(",") { p ->
            """{"project_id":$p,"item_ids":[${items.joinToString(",") { "\"$it\"" }}]}"""
        }}]}
        """.trimIndent()

    private fun ok(id: Long, vararg counts: Pair<String, Long>) =
        ContainerSweep.Reading(id, ContainerSweep.STATE_OK, counts.toMap())

    // ── The heartbeat ──────────────────────────────────────────────────────────

    @Test
    fun `the first push leaves at boot, so a bad token is a log line within a tick`() {
        val service = service()

        service.tickUntil("the boot heartbeat") { pushes.isNotEmpty() }

        // Empty, because nothing is tagged yet — but it still goes, which is what proves the token
        // works before anybody has waited a sweep interval to find out it does not.
        val body = pushes.first()
        assertTrue(body.contains("\"containers\":[]"), body)
        assertTrue(body.contains("\"world_id\":1"), body)
        assertTrue(body.contains("\"reporter_version\""), body)
    }

    // ── One pass per interval ──────────────────────────────────────────────────

    @Test
    fun `a pass reads every group once and then stops until the interval comes round`() {
        tagsBody = tagsResponse(tag(1), tag(2), tag(3))
        readings[1] = ok(1, "minecraft:iron_ingot" to 12)
        readings[2] = ok(2)
        readings[3] = ok(3)

        val service = service(sweepSeconds = 30)
        service.tickUntil("the first sweep") { groupsRead.size >= 3 }

        // Reading continuously between pushes would burn ticks producing readings nobody sends.
        val afterFirstPass = groupsRead.size
        repeat(40) { service.tick(reader) }
        assertEquals(afterFirstPass, groupsRead.size, "the sweep kept reading between passes")

        clock = clock.plus(Duration.ofSeconds(31))
        service.tickUntil("the second sweep") { groupsRead.size > afterFirstPass }
    }

    @Test
    fun `a pass is spread over ticks, so a big base is not one tick spike`() {
        tagsBody = tagsResponse(*(1L..10L).map { tag(it) }.toTypedArray())
        (1L..10L).forEach { readings[it] = ok(it) }

        val service = service(readsPerTick = 2)
        service.tickUntil("tags") { service.status().taggedContainers == 10 }

        val before = groupsRead.size
        service.tick(reader)
        assertEquals(2, groupsRead.size - before, "reads_per_tick was not respected")
    }

    // ── The diff ───────────────────────────────────────────────────────────────

    @Test
    fun `only changed containers are pushed, and an unchanged one is never resent`() {
        tagsBody = tagsResponse(tag(1), tag(2))
        readings[1] = ok(1, "minecraft:iron_ingot" to 12)
        readings[2] = ok(2, "minecraft:iron_ingot" to 5)

        val service = service()
        service.tickUntil("the first content push") { pushes.any { it.contains("\"count\":12") } }

        pushes.clear()
        readings[1] = ok(1, "minecraft:iron_ingot" to 20)
        clock = clock.plus(Duration.ofSeconds(31))
        service.tickUntil("the second push") { pushes.isNotEmpty() }

        val body = pushes.last()
        assertTrue(body.contains("\"count\":20"), "the change was not pushed: $body")
        assertTrue(!body.contains("\"id\":2"), "an unchanged container was resent: $body")
    }

    @Test
    fun `a missing container is pushed once, not on every pass`() {
        tagsBody = tagsResponse(tag(1))
        readings[1] = ContainerSweep.Reading(1, ContainerSweep.STATE_MISSING, emptyMap())

        val service = service()
        service.tickUntil("the missing report") { pushes.any { it.contains("\"missing\"") } }

        // `missing` is a state, not an event. Re-sending it every 30s forever would be pure noise
        // in the log, the request count and the webapp's write volume.
        pushes.clear()
        clock = clock.plus(Duration.ofSeconds(31))
        service.tickUntil("the next push") { pushes.isNotEmpty() }
        assertTrue(!pushes.last().contains("\"missing\""), "missing was resent: ${pushes.last()}")
    }

    @Test
    fun `a failed push is retried, not silently dropped`() {
        tagsBody = tagsResponse(tag(1))
        readings[1] = ok(1, "minecraft:iron_ingot" to 64)
        pushStatus = 500
        pushBody = """{"error":"server_error"}"""

        val service = service()
        service.tickUntil("the rejected push") { pushes.any { it.contains("\"count\":64") } }
        assertNotNull(service.status().lastError)
        assertNull(service.status().lastPushAt, "a 500 must not count as a push")

        pushes.clear()
        pushStatus = 200
        pushBody = """{"accepted":1,"rejected":0,"projects_recomputed":1}"""
        clock = clock.plus(Duration.ofSeconds(31))

        // Advancing the baseline on a failure would lose this reading until the count next changed.
        // Wait on the *accepted* push, not on the request arriving: the reply is applied a tick
        // later, and asserting in between would be testing the test's timing.
        service.tickUntil("the accepted retry") { service.status().lastPushAt != null }
        assertTrue(pushes.any { it.contains("\"count\":64") }, "the reading was not re-sent")
        assertNull(service.status().lastError)
    }

    // ── What the sweep is asked to look for ────────────────────────────────────

    @Test
    fun `the sweep is told exactly which items the project cares about`() {
        tagsBody = tagsResponse(tag(1), items = listOf("minecraft:iron_ingot", "minecraft:oak_log"))
        readings[1] = ok(1)

        val service = service()
        service.tickUntil("a read") { interestSeen.isNotEmpty() }

        assertEquals(setOf("minecraft:iron_ingot", "minecraft:oak_log"), interestSeen.first())
    }

    @Test
    fun `a project with nothing to measure is reported as such, not left looking broken`() {
        tagsBody = """
            {"world_id":1,
             "containers":[{"id":1,"project_id":7,"dimension":"minecraft:overworld",
                            "x":1,"y":64,"z":0,"group_key":"1,64,0","kind":"chest"}],
             "items_of_interest":[]}
        """.trimIndent()
        readings[1] = ok(1)

        val service = service()
        service.tickUntil("tags") { service.status().taggedContainers == 1 }

        // An empty interest set reports nothing rather than everything — safe, but from the outside
        // indistinguishable from a broken sweep, so `/seam status` has to be able to say why.
        assertEquals(listOf(7), service.status().projectsWithoutItemsOfInterest)
    }

    // ── Tags coming and going ──────────────────────────────────────────────────

    @Test
    fun `an untagged container stops occupying the diff`() {
        tagsBody = tagsResponse(tag(1), tag(2))
        readings[1] = ok(1, "minecraft:iron_ingot" to 1)
        readings[2] = ok(2, "minecraft:iron_ingot" to 2)

        val service = service()
        service.tickUntil("both tags") { service.status().taggedContainers == 2 }

        tagsBody = tagsResponse(tag(1))
        clock = clock.plus(Duration.ofSeconds(ReporterService.TAG_PULL_SECONDS + 1))
        service.tickUntil("the shorter tag list") { service.status().taggedContainers == 1 }
        assertEquals(1, service.status().groups)
    }

    @Test
    fun `the version the webapp is told matches the mod's own`() {
        // world settings shows this; a stale string there is worse than none.
        val modVersion = java.io.File("gradle.properties").readLines()
            .first { it.startsWith("modVersion=") }.substringAfter('=').trim()

        assertTrue(
            ReporterService.REPORTER_VERSION.startsWith("$modVersion+"),
            "REPORTER_VERSION=${ReporterService.REPORTER_VERSION} but modVersion=$modVersion",
        )
    }
}
