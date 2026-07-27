package gg.seam.mod.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.seam.mod.api.SeamApiClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [SeamSync.flush] against a loopback server. `commit` is injected so the flush can be
 * observed without a running game, which is also how the production wiring keeps [WorldDataStore]
 * out of the flush itself.
 */
class SeamSyncTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private var responseStatus = 200
    private var responseBody = """{"resources":[]}"""
    private val requests = mutableListOf<Pair<String, String>>() // method+path to body

    private fun client() = SeamApiClient(baseUrl = { baseUrl }, token = { "test-token" })

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
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        requests += "${exchange.requestMethod} ${exchange.requestURI.path}" to body
        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun flush(data: WorldData, client: SeamApiClient = client()): Pair<FlushResult, WorldData?> {
        var committed: WorldData? = null
        val result = SeamSync.flush(
            client = client,
            data = data,
            commit = { r ->
                var next = data
                r.syncedProjects.forEach { next = next.withProjectReset(it) }
                r.syncedTasks.forEach { next = next.withoutPendingTask(it.projectId, it.taskId) }
                committed = next
            },
            now = { 42L },
        ).get(10, TimeUnit.SECONDS)
        return result to committed
    }

    @Test
    fun `an empty queue makes no requests`() {
        val (result, _) = flush(WorldData(seamWorldId = 1))

        assertEquals(0, result.pushed)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `resource counts are pushed as an absolute set and then dropped locally`() {
        val data = WorldData(seamWorldId = 1)
            .withCount(5, "minecraft:iron_ingot", 128)
            .withCount(5, "minecraft:stone", 0)

        val (result, committed) = flush(data)

        assertNull(result.failure)
        assertEquals(setOf(5), result.syncedProjects)
        val (request, body) = requests.single()
        assertEquals("POST /api/v1/projects/5/resources/sync", request)
        // The zeroed item must be in the payload — otherwise "I emptied this" would read to the
        // server as "no opinion" and the old count would stand.
        assertTrue(body.contains(""""item_id":"minecraft:stone","collected":0"""), "got: $body")
        assertTrue(body.contains(""""item_id":"minecraft:iron_ingot","collected":128"""), "got: $body")
        // Cleared locally: Seam is authoritative now, so a later web edit isn't shadowed.
        assertEquals(0, committed!!.queuedWrites)
        assertTrue(committed.resourceCounts.isEmpty())
    }

    @Test
    fun `task toggles are pushed and cleared`() {
        responseBody = """{"id":9,"name":"Dig","completed":true}"""
        val data = WorldData(seamWorldId = 1).withPendingTask(4, 9, completed = true)

        val (result, committed) = flush(data)

        assertEquals(listOf(TaskRef(4, 9)), result.syncedTasks)
        val (request, body) = requests.single()
        assertEquals("PUT /api/v1/projects/4/tasks/9", request)
        assertEquals("""{"completed":true}""", body)
        assertNull(committed!!.pendingTask(4, 9))
    }

    @Test
    fun `an unreachable server leaves everything queued for the next attempt`() {
        val data = WorldData(seamWorldId = 1)
            .withCount(5, "minecraft:stone", 3)
            .withPendingTask(4, 9, completed = true)

        val (result, committed) = flush(data, SeamApiClient(baseUrl = { "http://127.0.0.1:1" }, token = { "t" }))

        assertEquals(0, result.pushed)
        assertTrue(result.failure != null)
        assertIs<SyncState.Failed>(SeamSync.state)
        // commit() ran with nothing to clear, so the queue is intact.
        assertEquals(2, (committed ?: data).queuedWrites)
    }

    @Test
    fun `the flush stops at the first failure and keeps the rest queued`() {
        responseStatus = 500
        responseBody = """{"error":"server_error"}"""
        val data = WorldData(seamWorldId = 1)
            .withCount(1, "minecraft:stone", 1)
            .withCount(2, "minecraft:stone", 1)
            .withCount(3, "minecraft:stone", 1)

        val (result, _) = flush(data)

        assertEquals(0, result.syncedProjects.size)
        // One attempt, not three: if the server is failing for one it's failing for all, and three
        // error messages are no more useful than one.
        assertEquals(1, requests.size)
    }

    @Test
    fun `a task deleted in the web app is dropped rather than retried forever`() {
        responseStatus = 404
        responseBody = """{"error":"not_found","message":"Task not found"}"""
        val data = WorldData(seamWorldId = 1).withPendingTask(4, 9, completed = true)

        val (result, committed) = flush(data)

        assertNull(result.failure, "a 404 must not wedge the queue")
        assertEquals(listOf(TaskRef(4, 9)), result.syncedTasks)
        assertEquals(0, committed!!.queuedWrites)
    }

    @Test
    fun `a successful flush records when it happened`() {
        val (_, _) = flush(WorldData(seamWorldId = 1).withCount(5, "minecraft:stone", 1))

        assertEquals(SyncState.Synced(42L), SeamSync.state)
    }
}
