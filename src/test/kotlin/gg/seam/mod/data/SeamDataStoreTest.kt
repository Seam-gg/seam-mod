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
 * Drives [SeamDataStore.refresh] against a loopback server. The store's parameters exist precisely
 * so this needs no running game.
 */
class SeamDataStoreTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private var responseStatus = 200
    private var responseBody = "[]"
    private var lastPath: String? = null

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
        lastPath = exchange.requestURI.path
        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun refresh(isLinked: Boolean = true, seamWorldId: Int? = 3): SeamData =
        SeamDataStore.refresh(
            client = client(),
            isLinked = isLinked,
            seamWorldId = seamWorldId,
            now = { 1_000L },
        ).get(10, TimeUnit.SECONDS)

    @Test
    fun `an unlinked account short-circuits without a request`() {
        val state = refresh(isLinked = false)

        assertIs<SeamData.Unlinked>(state)
        assertNull(lastPath, "no request should have been made")
    }

    @Test
    fun `a world with no Seam binding short-circuits without a request`() {
        val state = refresh(seamWorldId = null)

        assertIs<SeamData.Unmapped>(state)
        assertNull(lastPath, "no request should have been made")
    }

    @Test
    fun `a successful pull publishes the projects and the fetch time`() {
        responseBody = """
            [{"id":9,"name":"Iron Farm","stage":"GATHERING","state":"IN_PROGRESS",
              "resources":[{"item_id":"minecraft:iron_ingot","name":"Iron Ingot","required":320,"collected":64}],
              "tasks":[{"id":1,"name":"Dig","completed":false}]}]
        """.trimIndent()

        val state = refresh()

        assertIs<SeamData.Loaded>(state)
        assertEquals("/api/v1/worlds/3/projects", lastPath)
        assertEquals(1_000L, state.fetchedAtMillis)
        assertEquals("Iron Farm", state.projects.single().name)
        assertEquals(SeamDataStore.projects, state.projects)
    }

    @Test
    fun `a Seam world with no projects loads as empty, not as a failure`() {
        responseBody = "[]"

        val state = refresh()

        assertIs<SeamData.Loaded>(state)
        assertTrue(state.projects.isEmpty())
    }

    @Test
    fun `a revoked token reads as unlinked rather than a generic failure`() {
        responseStatus = 401
        responseBody = """{"error":"invalid_token","message":"Invalid, expired, or revoked token"}"""

        assertIs<SeamData.Unlinked>(refresh())
    }

    @Test
    fun `a server error surfaces the server's message to the player`() {
        responseStatus = 403
        responseBody = """{"error":"forbidden","message":"Not a member of this world"}"""

        val state = refresh()

        assertIs<SeamData.Failed>(state)
        assertTrue(state.reason.contains("Not a member of this world"), "got: ${state.reason}")
    }

    @Test
    fun `an unreachable server is a failure and leaves no stale projects`() {
        val state = SeamDataStore.refresh(
            client = SeamApiClient(baseUrl = { "http://127.0.0.1:1" }, token = { "t" }),
            isLinked = true,
            seamWorldId = 3,
        ).get(10, TimeUnit.SECONDS)

        assertIs<SeamData.Failed>(state)
        assertTrue(SeamDataStore.projects.isEmpty())
    }
}
