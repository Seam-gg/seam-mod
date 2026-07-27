package gg.seam.mod.data

import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.ProjectDto
import gg.seam.mod.api.SeamApi
import gg.seam.mod.api.SeamApiClient
import gg.seam.mod.api.describe
import java.util.concurrent.CompletableFuture

/**
 * What the notebook currently knows about the Seam side (MCO-268).
 *
 * The states are exhaustive on purpose: every one of them is a thing the notebook has to say to the
 * player, and each has a different fix. "No projects" because you never linked an account is a very
 * different message from "no projects" because the request failed.
 */
sealed interface SeamData {

    /** No Seam account linked — Settings → Link account. */
    data object Unlinked : SeamData

    /** Linked, but this local world isn't bound to a Seam world yet — Settings → Seam world. */
    data object Unmapped : SeamData

    /** A pull is in flight. */
    data object Loading : SeamData

    /** Projects as of [fetchedAtMillis]. Possibly empty — a real Seam world with no projects yet. */
    data class Loaded(val projects: List<ProjectDto>, val fetchedAtMillis: Long) : SeamData

    /** The pull failed; [reason] is player-facing. */
    data class Failed(val reason: String) : SeamData
}

/**
 * Holds the projects pulled for the current world and the state of the last pull.
 *
 * Cached per world: [refresh] replaces the whole set, and [WorldDataStore.unload] clears it on
 * disconnect so a second world never renders the first one's projects. Reads happen on the client
 * thread every frame, so [state] is a volatile publish rather than anything synchronised.
 */
object SeamDataStore {

    @Volatile
    var state: SeamData = SeamData.Unlinked
        private set

    /** Projects from the last successful pull, or empty in every other state. */
    val projects: List<ProjectDto> get() = (state as? SeamData.Loaded)?.projects.orEmpty()

    /**
     * Pull projects for the bound Seam world and publish the outcome to [state].
     *
     * The parameters exist so this can be driven in tests without a game; production callers use
     * the defaults, which read the live config and world binding at call time.
     */
    fun refresh(
        client: SeamApiClient = SeamApi.client,
        isLinked: Boolean = SeamApi.isLinked,
        seamWorldId: Int? = WorldDataStore.current.seamWorldId,
        now: () -> Long = System::currentTimeMillis,
    ): CompletableFuture<SeamData> {
        if (!isLinked) return publish(SeamData.Unlinked)
        if (seamWorldId == null) return publish(SeamData.Unmapped)

        state = SeamData.Loading
        return client.getProjects(seamWorldId).thenApply { result ->
            val next = when (result) {
                is ApiResult.Ok -> SeamData.Loaded(result.value, now())
                // A stale token reads as a plain 401; say so rather than "could not load projects".
                is ApiResult.Error ->
                    if (result.status == 401) SeamData.Unlinked
                    else SeamData.Failed("Could not load projects: ${result.describe()}")
                is ApiResult.Failure -> SeamData.Failed("Could not reach Seam: ${result.describe()}")
            }
            state = next
            next
        }
    }

    /**
     * Refresh only if the cache is older than [maxAgeMillis] (or was never filled). Screens call
     * this from `init()`, which Minecraft re-runs on every window resize — without the guard,
     * dragging a window edge would hammer the API.
     */
    fun refreshIfStale(maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS, now: () -> Long = System::currentTimeMillis) {
        val current = state
        if (current is SeamData.Loading) return
        if (current is SeamData.Loaded && now() - current.fetchedAtMillis < maxAgeMillis) return
        refresh(now = now)
    }

    /** Drop cached projects (on disconnect, or when the world binding changes). */
    fun clear() {
        state = if (!SeamApi.isLinked) SeamData.Unlinked else SeamData.Unmapped
    }

    private fun publish(value: SeamData): CompletableFuture<SeamData> {
        state = value
        return CompletableFuture.completedFuture(value)
    }

    /** Short enough that reopening the notebook shows fresh data, long enough to survive a resize. */
    private const val DEFAULT_MAX_AGE_MILLIS = 10_000L
}
