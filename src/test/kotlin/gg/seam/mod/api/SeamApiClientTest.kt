package gg.seam.mod.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
 * Exercises [SeamApiClient] against a real loopback HTTP server (JDK built-in — no test deps), so
 * the request shape, the bearer header and the status→[ApiResult] mapping are all checked end to
 * end rather than mocked.
 */
class SeamApiClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** What the server should answer with next, and what it last received. */
    private var responseStatus = 200
    private var responseBody = "{}"
    private var lastPath: String? = null
    private var lastMethod: String? = null
    private var lastAuthorization: String? = null
    private var lastBody: String? = null

    private var token: String? = "test-token"

    private fun client() = SeamApiClient(baseUrl = { baseUrl }, token = { token })

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.executor = null
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange) {
        lastPath = exchange.requestURI.path
        lastMethod = exchange.requestMethod
        lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
        lastBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun <T> await(future: java.util.concurrent.CompletableFuture<T>): T =
        future.get(10, TimeUnit.SECONDS)

    @Test
    fun `a successful read decodes the body and sends the bearer token`() {
        responseBody = """[{"id":4,"name":"Survival","description":"","version":"1_21","total_projects":2,"completed_projects":0}]"""

        val result = await(client().getWorlds())

        assertIs<ApiResult.Ok<List<WorldDto>>>(result)
        assertEquals(4, result.value.single().id)
        assertEquals("/api/v1/worlds", lastPath)
        assertEquals("GET", lastMethod)
        assertEquals("Bearer test-token", lastAuthorization)
    }

    @Test
    fun `project ids are placed in the path and the sync body uses the wire format`() {
        responseBody = """{"resources":[{"item_id":"minecraft:stone","name":"Stone","required":10,"collected":10}]}"""

        val result = await(client().syncResources(12, listOf(SyncResourceItem("minecraft:stone", 10))))

        assertIs<ApiResult.Ok<ResourcesResponse>>(result)
        assertEquals("/api/v1/projects/12/resources/sync", lastPath)
        assertEquals("POST", lastMethod)
        assertEquals("""{"resources":[{"item_id":"minecraft:stone","collected":10}]}""", lastBody)
        assertEquals(10, result.value.resources.single().collected)
    }

    @Test
    fun `task updates PUT to the nested task path`() {
        responseBody = """{"id":9,"name":"Dig","completed":true}"""

        val result = await(client().updateTask(projectId = 3, taskId = 9, completed = true))

        assertIs<ApiResult.Ok<TaskDto>>(result)
        assertEquals("/api/v1/projects/3/tasks/9", lastPath)
        assertEquals("PUT", lastMethod)
        assertTrue(result.value.completed)
    }

    @Test
    fun `a non-2xx response surfaces the server's error code`() {
        responseStatus = 403
        responseBody = """{"error":"forbidden","message":"Not a member of this world"}"""

        val result = await(client().getProjects(worldId = 1))

        assertIs<ApiResult.Error>(result)
        assertEquals(403, result.status)
        assertEquals("forbidden", result.code)
        assertEquals("Not a member of this world", result.message)
    }

    @Test
    fun `a pending device-code poll arrives as an error carrying the RFC code`() {
        responseStatus = 400
        responseBody = """{"error":"authorization_pending"}"""

        val result = await(client().pollDeviceCode("device-code"))

        assertIs<ApiResult.Error>(result)
        assertEquals("authorization_pending", result.code)
        assertEquals("/api/v1/auth/device-code/poll", lastPath)
        assertEquals("""{"device_code":"device-code"}""", lastBody)
        // The device-code endpoints are unauthenticated — no bearer header even when a token exists.
        assertNull(lastAuthorization)
    }

    @Test
    fun `an error body that isn't JSON still yields a usable status code`() {
        responseStatus = 502
        responseBody = "<html>bad gateway</html>"

        val result = await(client().getWorlds())

        assertIs<ApiResult.Error>(result)
        assertEquals(502, result.status)
        assertEquals("http_502", result.code)
    }

    @Test
    fun `a malformed success body is a failure, not a silent empty result`() {
        responseBody = "not json at all"

        val result = await(client().getWorlds())

        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun `an authenticated call without a linked account short-circuits before any request`() {
        token = null

        val result = await(client().getWorlds())

        assertEquals(ApiResult.NOT_LINKED, result)
        assertNull(lastPath, "no request should have reached the server")
    }

    @Test
    fun `an unreachable server is a failure rather than an exception`() {
        val unreachable = SeamApiClient(baseUrl = { "http://127.0.0.1:1" }, token = { "t" })

        val result = await(unreachable.getWorlds())

        assertIs<ApiResult.Failure>(result)
    }
}
