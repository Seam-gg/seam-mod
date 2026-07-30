package gg.seam.mod.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * HTTP client for the mc-org JSON API (MCO-266).
 *
 * JDK [HttpClient], not ktor — a handful of JSON calls don't justify Jar-in-Jar, and ktor's Netty
 * would collide with Minecraft's own (docs/fabric-1.21.11-reference.md §0/§7).
 *
 * **Threading contract:** every method returns immediately with a [CompletableFuture]; the request,
 * the response read and the JSON parse all happen on [HttpClient]'s executor. Nothing here touches
 * Minecraft state — callers marshal back with [gg.seam.mod.util.onClientThread] before they render,
 * chat, or mutate the world.
 *
 * [baseUrl] and [token] are suppliers, not values, so the client picks up a re-linked account or a
 * changed dev endpoint without being rebuilt (and so tests can inject both).
 */
class SeamApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val userAgent: String = "SeamNotebook (Fabric)",
    private val http: HttpClient = defaultHttpClient(),
) {

    // ── Auth: device-code flow (unauthenticated) ───────────────────────────────

    /** `POST /auth/device-code` — start a link. The response carries the code the player types on the web. */
    fun createDeviceCode(): CompletableFuture<ApiResult<DeviceCodeResponse>> =
        send(DeviceCodeResponse.serializer()) {
            request("/auth/device-code", authenticated = false)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", JSON)
                .build()
        }

    /**
     * `POST /auth/device-code/poll` — exchange an approved device code for a token.
     *
     * While the player hasn't approved yet the server answers 400 with an RFC-8628 error code, so a
     * pending poll surfaces as [ApiResult.Error] with `code = "authorization_pending"` (or
     * `slow_down` / `expired_token` / `access_denied`). See [gg.seam.mod.auth.PollPolicy].
     */
    fun pollDeviceCode(deviceCode: String): CompletableFuture<ApiResult<PollSuccessResponse>> =
        send(PollSuccessResponse.serializer()) {
            request("/auth/device-code/poll", authenticated = false)
                .POST(jsonBody(PollRequest.serializer(), PollRequest(deviceCode)))
                .header("Content-Type", JSON)
                .build()
        }

    /** `DELETE /auth/token` — revoke the token this client is currently holding. */
    fun revokeToken(): CompletableFuture<ApiResult<OkResponse>> =
        send(OkResponse.serializer()) { request("/auth/token").DELETE().build() }

    // ── Reads ──────────────────────────────────────────────────────────────────

    /** `GET /worlds` — every Seam world the linked account can see. */
    fun getWorlds(): CompletableFuture<ApiResult<List<WorldDto>>> =
        send(listSerializer(WorldDto.serializer())) { request("/worlds").GET().build() }

    /** `GET /worlds/{id}/projects` — projects for one world, each with its resources and tasks. */
    fun getProjects(worldId: Int): CompletableFuture<ApiResult<List<ProjectDto>>> =
        send(listSerializer(ProjectDto.serializer())) {
            request("/worlds/${worldId.pathSegment()}/projects").GET().build()
        }

    // ── Writes ─────────────────────────────────────────────────────────────────

    /**
     * `POST /projects/{id}/resources/sync` — absolute set, not a delta: each entry's `collected`
     * replaces the stored count (the server clamps to `[0, required]`). Returns the project's
     * resources as they stand after the write.
     */
    fun syncResources(
        projectId: Int,
        resources: List<SyncResourceItem>,
    ): CompletableFuture<ApiResult<ResourcesResponse>> =
        send(ResourcesResponse.serializer()) {
            request("/projects/${projectId.pathSegment()}/resources/sync")
                .POST(jsonBody(SyncRequest.serializer(), SyncRequest(resources)))
                .header("Content-Type", JSON)
                .build()
        }

    /** `PUT /projects/{id}/tasks/{id}` — set a task's completion state. */
    fun updateTask(
        projectId: Int,
        taskId: Int,
        completed: Boolean,
    ): CompletableFuture<ApiResult<TaskDto>> =
        send(TaskDto.serializer()) {
            request("/projects/${projectId.pathSegment()}/tasks/${taskId.pathSegment()}")
                .PUT(jsonBody(TaskUpdateRequest.serializer(), TaskUpdateRequest(completed)))
                .header("Content-Type", JSON)
                .build()
        }

    // ── Plumbing ───────────────────────────────────────────────────────────────

    /**
     * Builder for [path] under `<baseUrl>/api/v1`. [authenticated] requests carry the bearer token;
     * building one without a linked account throws [NotLinkedException], which [send] converts into
     * [ApiResult.NOT_LINKED] rather than a network round trip.
     */
    private fun request(path: String, authenticated: Boolean = true): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl().trimEnd('/') + API_PREFIX + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", JSON)
            .header("User-Agent", userAgent)
        if (authenticated) {
            val bearer = token()?.takeIf { it.isNotBlank() } ?: throw NotLinkedException()
            builder.header("Authorization", "Bearer $bearer")
        }
        return builder
    }

    /**
     * [buildRequest] is a lambda, not a value, so anything it throws — a missing token, a malformed
     * base URL — lands in the returned future instead of blowing up the caller. Every path out of a
     * Seam API call is an [ApiResult].
     */
    private fun <T> send(
        deserializer: DeserializationStrategy<T>,
        buildRequest: () -> HttpRequest,
    ): CompletableFuture<ApiResult<T>> =
        try {
            http.sendAsync(buildRequest(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle { response, thrown ->
                    if (thrown != null) failureFor(thrown) else decode(response, deserializer)
                }
        } catch (e: Exception) {
            CompletableFuture.completedFuture(failureFor(e))
        }

    private fun <T> decode(
        response: HttpResponse<String>,
        deserializer: DeserializationStrategy<T>,
    ): ApiResult<T> {
        val status = response.statusCode()
        val body = response.body().orEmpty()
        if (status !in 200..299) return errorFor(status, body)
        return try {
            ApiResult.Ok(SeamJson.json.decodeFromString(deserializer, body))
        } catch (e: Exception) {
            ApiResult.Failure(e)
        }
    }

    /** Non-2xx: prefer the server's `{error, message}` body, fall back to the bare status. */
    private fun errorFor(status: Int, body: String): ApiResult.Error {
        val parsed = runCatching {
            SeamJson.json.decodeFromString(ApiErrorResponse.serializer(), body)
        }.getOrNull()
        return ApiResult.Error(
            status = status,
            code = parsed?.error?.takeIf { it.isNotBlank() } ?: "http_$status",
            message = parsed?.message,
        )
    }

    private fun <T> failureFor(thrown: Throwable): ApiResult<T> {
        // CompletableFuture wraps everything thrown inside the pipeline (incl. our request builder).
        val cause = (thrown as? CompletionException)?.cause ?: thrown
        return if (cause is NotLinkedException) ApiResult.NOT_LINKED else ApiResult.Failure(cause)
    }

    private fun <T> jsonBody(serializer: SerializationStrategy<T>, value: T): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofString(SeamJson.json.encodeToString(serializer, value), StandardCharsets.UTF_8)

    /** Raised when an authenticated call is attempted with no linked account. Never escapes [send]. */
    private class NotLinkedException : IllegalStateException("No Seam account is linked")

    private companion object {
        const val API_PREFIX = "/api/v1"
        const val JSON = "application/json"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // Follow the http→https upgrade the webapp issues; never downgrade.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        /** Ids are ints today, but encode anyway so a future string id can't smuggle a path segment. */
        fun Int.pathSegment(): String = URLEncoder.encode(toString(), StandardCharsets.UTF_8)

        fun <T> listSerializer(element: kotlinx.serialization.KSerializer<T>) =
            kotlinx.serialization.builtins.ListSerializer(element)
    }
}
