package gg.seam.mod.api

import gg.seam.mod.SeamClient
import gg.seam.mod.data.ConfigStore
import net.fabricmc.loader.api.FabricLoader

/**
 * The mod's single [SeamApiClient] instance and the rules for where it points.
 *
 * Base URL resolution, first match wins:
 * 1. `-Dseam.apiBaseUrl=…` — the dev-time override (`runClient` against a local webapp)
 * 2. `SEAM_API_BASE_URL` environment variable
 * 3. `api_base_url` in `seam/config.json` — for players on a self-hosted Seam
 * 4. [DEFAULT_BASE_URL]
 *
 * Resolved per call rather than cached, so editing config.json or relaunching with a different
 * property takes effect without a restart.
 */
object SeamApi {

    const val DEFAULT_BASE_URL = "https://app.seam.gg"
    const val BASE_URL_PROPERTY = "seam.apiBaseUrl"
    const val BASE_URL_ENV = "SEAM_API_BASE_URL"

    val client: SeamApiClient by lazy {
        SeamApiClient(
            baseUrl = ::baseUrl,
            token = { ConfigStore.current.auth?.token },
            userAgent = userAgent(),
        )
    }

    /** The webapp origin this mod talks to (no trailing slash, no `/api/v1` suffix). */
    fun baseUrl(): String =
        firstNonBlank(
            System.getProperty(BASE_URL_PROPERTY),
            System.getenv(BASE_URL_ENV),
            ConfigStore.current.apiBaseUrl,
        )?.trimEnd('/') ?: DEFAULT_BASE_URL

    /** True once the player has linked a Seam account (a token is on disk). */
    val isLinked: Boolean get() = !ConfigStore.current.auth?.token.isNullOrBlank()

    /** The linked account's Seam username, when known. */
    val linkedUsername: String? get() = ConfigStore.current.auth?.username

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

    private fun userAgent(): String {
        val version = runCatching {
            FabricLoader.getInstance().getModContainer(SeamClient.MOD_ID)
                .map { it.metadata.version.friendlyString }
                .orElse("dev")
        }.getOrDefault("dev")
        return "SeamCompanion/$version (Fabric)"
    }
}
