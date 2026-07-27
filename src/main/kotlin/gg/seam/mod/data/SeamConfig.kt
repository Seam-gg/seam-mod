package gg.seam.mod.data

import gg.seam.mod.SeamClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture

/**
 * Global, world-independent settings — the `seam/config.json` block from the v1 spec (§Data
 * Storage). Holds the account link ([auth]) and user preferences ([settings]). Loaded once at
 * client init and kept in [ConfigStore.current]; see docs/fabric-1.21.11-reference.md §7.
 *
 * MVP scope (MCO-258): the manual-counter path. Container-tag maps, gather/scan fields and the
 * project cache are deferred with Phase 2.
 */
@Serializable
data class SeamConfig(
    val version: Int = 1,
    val auth: AuthConfig? = null,
    val settings: SeamSettings = SeamSettings(),
    /** Override for the Seam webapp origin (self-hosting). Null = the public app; see [gg.seam.mod.api.SeamApi]. */
    @SerialName("api_base_url") val apiBaseUrl: String? = null,
)

/** Account link written after device-code auth (MCO-236). Absent until the user links Seam. */
@Serializable
data class AuthConfig(
    val token: String,
    val username: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null,
)

@Serializable
data class SeamSettings(
    @SerialName("sync_frequency") val syncFrequency: SyncFrequency = SyncFrequency.ON_OPEN,
    @SerialName("container_tags_visible") val containerTagsVisible: Boolean = true,
    @SerialName("container_tag_render_distance") val containerTagRenderDistance: Int = 12,
    @SerialName("player_inventory_mode") val playerInventoryMode: PlayerInventoryMode = PlayerInventoryMode.OFF,
    @SerialName("scan_ender_chest") val scanEnderChest: Boolean = false,
)

/** How often the notebook pulls from Seam. Unknown values coerce to the default (see SeamStorage.json). */
@Serializable
enum class SyncFrequency {
    @SerialName("on_open") ON_OPEN,
    @SerialName("periodic_10s") PERIODIC_10S,
    @SerialName("periodic_30s") PERIODIC_30S,
    @SerialName("periodic_60s") PERIODIC_60S,
}

/** Which projects the player-inventory contents count toward. */
@Serializable
enum class PlayerInventoryMode {
    @SerialName("off") OFF,
    @SerialName("active_gather_project") ACTIVE_GATHER_PROJECT,
    @SerialName("all_projects") ALL_PROJECTS,
}

/**
 * In-memory holder for the global config, backed by `seam/config.json`. [current] is read on the
 * client thread (volatile publish), all disk I/O runs on [SeamStorage]'s single I/O thread.
 */
object ConfigStore {
    private val path get() = SeamStorage.root.resolve("config.json")

    /** Latest known config. Starts at defaults so callers never see null before the first load. */
    @Volatile
    var current: SeamConfig = SeamConfig()
        private set

    /** Read config.json (defaults if missing/corrupt) and publish it to [current]. */
    fun loadAsync(): CompletableFuture<SeamConfig> =
        SeamStorage.loadAsync(path, SeamConfig.serializer()).thenApply { loaded ->
            val config = loaded ?: SeamConfig()
            current = config
            config
        }

    /** Apply [transform] to [current] (on the calling thread), publish it, then persist atomically on the I/O thread. */
    fun update(transform: (SeamConfig) -> SeamConfig): CompletableFuture<Void> {
        val next = transform(current)
        current = next
        return SeamStorage.saveAsync(path, SeamConfig.serializer(), next)
            .exceptionally { e ->
                SeamClient.logger.error("Failed to persist Seam config", e)
                null
            }
    }
}
