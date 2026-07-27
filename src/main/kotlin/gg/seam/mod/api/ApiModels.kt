package gg.seam.mod.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models for the mc-org JSON API (`/api/v1`). These mirror `app.mcorg.api.ApiDtos` in the
 * webapp field-for-field — that file is the stable contract, this is the client half of it. Keep
 * the two in sync when the API changes; `ignoreUnknownKeys` means the mod tolerates the server
 * adding fields, but never invents its own.
 *
 * Wire format is snake_case (via [SerialName]). See docs/fabric-1.21.11-reference.md §7.
 */

/** JSON codec for the API wire format — deliberately separate from [gg.seam.mod.data.SeamStorage.json] (disk format). */
object SeamJson {
    val json: Json = Json {
        ignoreUnknownKeys = true    // the server may add fields ahead of a mod release
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }
}

// ── Auth: device-code flow ─────────────────────────────────────────────────────

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("interval") val interval: Int,
)

@Serializable
data class PollRequest(
    @SerialName("device_code") val deviceCode: String,
)

@Serializable
data class PollSuccessResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("username") val username: String,
)

/**
 * Error body shared by every non-2xx response. Covers both the generic `{error, message}` shape and
 * the RFC-8628 poll bodies (`authorization_pending`, `slow_down`, `expired_token`, `access_denied`),
 * which carry only `error`.
 */
@Serializable
data class ApiErrorResponse(
    @SerialName("error") val error: String,
    @SerialName("message") val message: String? = null,
)

@Serializable
data class OkResponse(
    @SerialName("ok") val ok: Boolean = true,
)

// ── Read/write resources ───────────────────────────────────────────────────────

@Serializable
data class WorldDto(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("total_projects") val totalProjects: Int = 0,
    @SerialName("completed_projects") val completedProjects: Int = 0,
)

@Serializable
data class ResourceDto(
    @SerialName("item_id") val itemId: String,
    @SerialName("name") val name: String,
    @SerialName("required") val required: Int,
    @SerialName("collected") val collected: Int,
    @SerialName("source_type") val sourceType: String? = null,
    /**
     * Which client last set [collected]: `mod` (this mod's sync) or `manual` (the web app). Added
     * by MCO-284. Distinct from [sourceType], which is the item's acquisition type from the graph.
     */
    @SerialName("progress_source") val progressSource: String = "manual",
)

@Serializable
data class TaskDto(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("completed") val completed: Boolean,
)

@Serializable
data class ProjectDto(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("stage") val stage: String,
    @SerialName("state") val state: String,
    @SerialName("resources") val resources: List<ResourceDto> = emptyList(),
    @SerialName("tasks") val tasks: List<TaskDto> = emptyList(),
)

// ── Sync (absolute set) ────────────────────────────────────────────────────────

@Serializable
data class SyncResourceItem(
    @SerialName("item_id") val itemId: String,
    @SerialName("collected") val collected: Int,
)

@Serializable
data class SyncRequest(
    @SerialName("resources") val resources: List<SyncResourceItem>,
)

@Serializable
data class ResourcesResponse(
    @SerialName("resources") val resources: List<ResourceDto>,
)

@Serializable
data class TaskUpdateRequest(
    @SerialName("completed") val completed: Boolean,
)
