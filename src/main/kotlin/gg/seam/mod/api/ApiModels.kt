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

// ── Container tags (MCO-530) ───────────────────────────────────────────────────

/**
 * A tagged container, as `GET /worlds/{id}/containers` returns it.
 *
 * [groupKey] is shared by both halves of a double chest (the position, `"x,y,z"`, otherwise) — the
 * sweep dedupes on it so a joined chest is not counted twice. [lastSeenAt] and [state] are written
 * only by the server half: `unreadable` until a sweep has read the position, then `ok`, or
 * `missing` once the block is gone. Timestamps are ISO-8601 instants, kept as strings because the
 * mod only displays them.
 */
@Serializable
data class ContainerTagDto(
    @SerialName("id") val id: Long,
    @SerialName("project_id") val projectId: Int,
    @SerialName("dimension") val dimension: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("z") val z: Int,
    @SerialName("group_key") val groupKey: String = "",
    @SerialName("kind") val kind: String = "",
    @SerialName("tagged_by") val taggedBy: String? = null,
    @SerialName("tagged_at") val taggedAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("state") val state: String = "unreadable",
)

/**
 * Tag a position, or move the tag already there to another project — `POST
 * /worlds/{id}/containers` creates or replaces, keyed on the position.
 *
 * Omit [groupKey] for a single container and the server defaults it to the position; both halves of
 * a joined chest are posted with the same explicit key. [kind] must be one of the taggable blocks:
 * `chest`, `trapped_chest`, `barrel`, `shulker_box`, `hopper`, `dropper`, `dispenser`. A furnace is
 * an `Inventory` but is deliberately not taggable — counting its fuel and input is nobody's intent.
 */
@Serializable
data class ContainerTagRequest(
    @SerialName("dimension") val dimension: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("z") val z: Int,
    @SerialName("project_id") val projectId: Int,
    @SerialName("kind") val kind: String,
    @SerialName("group_key") val groupKey: String? = null,
)

@Serializable
data class ContainerTagsResponse(
    @SerialName("containers") val containers: List<ContainerTagDto> = emptyList(),
)

// ── Gathering plan (MCO-533) ───────────────────────────────────────────────────

/**
 * One node of a project's gathering plan, from `GET /projects/{id}/plan` — the HUD's **graph
 * items** mode, as opposed to the **target** items on [ProjectDto].
 *
 * The difference is the whole point of the second mode: a project asks for 64 hoppers, and the plan
 * says go mine iron and chop wood. TARGET answers "what does the build need"; GRAPH answers "what
 * am I doing this afternoon".
 *
 * [quantity] is a `Long` and must stay one — plan quantities expand far past `Int` on a large
 * project, and the HUD formats them with thousands separators.
 *
 * [activityGroup] is one of `NEEDS_ATTENTION`, `COLLECT_SUPPLIED`, `GATHER`, `HUNT`, `LOOT`,
 * `TRADE`, `SMELT`, `CRAFT`, `OTHER`; [status] is one of `RESOLVED`, `RAW_GATHER`, `SUPPLIED`,
 * `OPEN_TAG`, `BLOCKED`. A plan full of `NEEDS_ATTENTION` is a normal answer — the webapp returns
 * 200 with those nodes marked, and the HUD should render them rather than treat it as an error.
 *
 * The plan is **expensive to derive**, so it belongs on the structure cadence (project switch and
 * every ~5 minutes), never on the count poll. See `SeamSettings.storagePollSeconds` for the other.
 */
@Serializable
data class PlanActivityDto(
    @SerialName("item_id") val itemId: String,
    @SerialName("name") val name: String = "",
    @SerialName("quantity") val quantity: Long = 0,
    @SerialName("activity_group") val activityGroup: String = "OTHER",
    @SerialName("status") val status: String = "RESOLVED",
)

// ── The reporter: tags in, contents out (MCO-532) ──────────────────────────────

/**
 * The items one project's sweep should bother reporting — its target items plus its plan items.
 *
 * Report only these. Reporting everything found would send the webapp an inventory of somebody's
 * junk drawer, and quietly turn it into a whole-world item census.
 */
@Serializable
data class ItemsOfInterestDto(
    @SerialName("project_id") val projectId: Int,
    @SerialName("item_ids") val itemIds: List<String> = emptyList(),
)

/**
 * `GET /reporter/tags` — everything the sweep needs: which containers to read, and which items are
 * worth reporting from them. Re-pulled every ~60s, `items_of_interest` along with it.
 */
@Serializable
data class ReporterTagsResponse(
    @SerialName("world_id") val worldId: Int = 0,
    @SerialName("containers") val containers: List<ContainerTagDto> = emptyList(),
    @SerialName("items_of_interest") val itemsOfInterest: List<ItemsOfInterestDto> = emptyList(),
)

@Serializable
data class ReportedItemDto(
    @SerialName("item_id") val itemId: String,
    @SerialName("count") val count: Long,
)

/**
 * One container as the sweep found it.
 *
 * [state] is `ok`, `unreadable` (the chunk has not been loaded since tagging) or `missing` (the
 * block is gone). Only `ok` should carry [items]; the other two mean "do not count this", and the
 * webapp drops whatever it held.
 */
@Serializable
data class ReportedContainerDto(
    @SerialName("id") val id: Long,
    @SerialName("state") val state: String,
    @SerialName("seen_at") val seenAt: String,
    @SerialName("items") val items: List<ReportedItemDto> = emptyList(),
)

/**
 * `POST /reporter/contents` — a sweep's report.
 *
 * **Absolute for the containers it names.** Name only those whose contents changed since the last
 * push; everything unnamed keeps what it had, which is what lets a container in an unloaded chunk
 * go on counting. One writer means absolute is correct and self-healing — there is nothing to merge
 * and no ordering hazard, and a reporter restart costs one full re-push rather than a resync
 * protocol. That is why the reporter needs no memory of its own.
 *
 * [worldId] is omitted on a dedicated server, whose reporter token already fixes its world, and is
 * **required in singleplayer**, where the sweep pushes with the player's own token and nothing else
 * says which Seam world this is.
 *
 * An empty [containers] list is a valid push and is the heartbeat — there is no separate endpoint
 * for it, so a sweep with nothing to say should still send one.
 */
@Serializable
data class ReporterContentsRequest(
    @SerialName("world_id") val worldId: Int? = null,
    @SerialName("swept_at") val sweptAt: String? = null,
    @SerialName("reporter_version") val reporterVersion: String? = null,
    // No default, deliberately. `SeamJson` omits fields sitting at their default, so a default of
    // `emptyList()` would drop the key entirely from a heartbeat — and a body with no `containers`
    // reads, in an mc-org log, exactly like a malformed one. An empty list says "nothing changed"
    // out loud.
    @SerialName("containers") val containers: List<ReportedContainerDto>,
)

@Serializable
data class ReporterContentsResponse(
    @SerialName("accepted") val accepted: Int = 0,
    /** Named containers the server would not accept — not this world's. Worth logging. */
    @SerialName("rejected") val rejected: Int = 0,
    @SerialName("projects_recomputed") val projectsRecomputed: Int = 0,
)

// ── The HUD's count poll (MCO-532) ─────────────────────────────────────────────

/**
 * One measured count, from `GET /worlds/{id}/storage` — the frequent poll (~10s), deliberately
 * cheap. Never put the plan on this cadence.
 *
 * [measured] is what is in tagged containers. It is **not** [ResourceDto.collected], which stays
 * the human's typed-in number; the HUD shows `measured` over `required`, and the webapp is where
 * their disagreement gets resolved.
 *
 * [oldestSeenAt] is the oldest contributing reading — how much to trust the number.
 */
@Serializable
data class WorldStorageDto(
    @SerialName("project_id") val projectId: Int,
    @SerialName("item_id") val itemId: String,
    @SerialName("measured") val measured: Long = 0,
    @SerialName("container_count") val containerCount: Int = 0,
    @SerialName("oldest_seen_at") val oldestSeenAt: String? = null,
)

// ── Is anything reading these containers? (MCO-536) ────────────────────────────

/**
 * `GET /worlds/{id}/reporter` — whether a server is reading this world's tagged containers.
 *
 * Tagging a chest and seeing nothing happen has two causes that look identical in game: no reporter
 * is connected, or one is connected to a **different** Seam world. Both leave tags piling up and
 * numbers never moving, and they have different fixes, so the notebook has to be able to tell them
 * apart without the player opening a browser.
 *
 * [configured] and [connected] are separate on purpose:
 * - neither — nobody has minted a token for this world.
 * - configured only — a token exists but no server has ever used it.
 * - both — a reporter has pushed, and [lastSeenAt] says whether it is *still* alive.
 *
 * [lastSeenAt] is the reporter **token's** last use, not a container's last reading. That
 * distinction is the whole point: a container in an unloaded chunk is deliberately never re-read,
 * so per-container timestamps go stale while the reporter is perfectly healthy. Never derive
 * liveness from [ContainerTagDto.lastSeenAt] — it would tell a player nothing is watching every
 * time they walk away from their storage room.
 */
@Serializable
data class ReporterStatusDto(
    @SerialName("configured") val configured: Boolean = false,
    @SerialName("connected") val connected: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("reporter_version") val reporterVersion: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("server_count") val serverCount: Int = 0,
)
