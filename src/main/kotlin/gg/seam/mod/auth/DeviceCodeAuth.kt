package gg.seam.mod.auth

import gg.seam.mod.SeamClient
import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.DeviceCodeResponse
import gg.seam.mod.api.PollSuccessResponse
import gg.seam.mod.api.SeamApi
import gg.seam.mod.api.describe
import gg.seam.mod.chat.SeamChat
import gg.seam.mod.data.AuthConfig
import gg.seam.mod.data.ConfigStore
import gg.seam.mod.util.Browser
import gg.seam.mod.util.onClientThread
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Device-code account linking (MCO-267), the RFC 8628 flow the webapp exposes at
 * `/api/v1/auth/device-code`:
 *
 * 1. [start] asks Seam for a device code and shows the player a `user_code` plus a clickable
 *    verification link in chat.
 * 2. The player opens the link in a browser, signs in, and approves the code.
 * 3. Meanwhile we poll off-thread on the schedule the server advertised, obeying `slow_down`.
 * 4. On approval the token lands in `seam/config.json` and the mod is linked.
 *
 * All network work happens on the API client's executor and the poll timer's own daemon thread;
 * only chat and clipboard writes hop to the client thread. Nothing here ever blocks a game tick.
 */
object DeviceCodeAuth {

    sealed interface State {
        /** No account linked and no link in progress. */
        data object Idle : State

        /** Waiting for the device code itself. */
        data object Starting : State

        /** Code issued; the player has until [expiresAtMillis] to approve it in a browser. */
        data class AwaitingApproval(
            val userCode: String,
            val verificationUri: String,
            val expiresAtMillis: Long,
        ) : State

        /** An account is linked. */
        data class Linked(val username: String) : State

        /** The last attempt failed; cleared by the next [start]. */
        data class Failed(val reason: String) : State
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "seam-auth").apply { isDaemon = true }
    }

    /** Non-null only while a link attempt is running (or has just failed); otherwise config decides. */
    @Volatile
    private var transient: State? = null

    /**
     * Bumped by [start], [cancel] and [unlink]. Every scheduled poll captures the value it was
     * created under and does nothing if it no longer matches, so a cancelled flow can't come back
     * from the dead and re-link the account.
     */
    @Volatile
    private var generation = 0

    /**
     * Current link state. Falls back to what's on disk, so a linked account shows as [State.Linked]
     * on the next launch without replaying anything.
     */
    val state: State
        get() = transient
            ?: if (SeamApi.isLinked) State.Linked(SeamApi.linkedUsername.orEmpty()) else State.Idle

    /** True while a link attempt is in flight — the UI offers "Cancel" instead of "Link Account". */
    val isLinking: Boolean get() = state is State.Starting || state is State.AwaitingApproval

    /** Begin (or restart) linking. No-op if an attempt is already running. */
    fun start() {
        if (isLinking) return
        val attempt = ++generation
        transient = State.Starting

        SeamApi.client.createDeviceCode().thenAccept { result ->
            if (attempt != generation) return@thenAccept
            when (result) {
                is ApiResult.Ok -> beginPolling(attempt, result.value)
                else -> fail(attempt, "Could not start linking: ${result.describe()}")
            }
        }
    }

    /** Abandon an in-flight attempt. The device code simply expires server-side. */
    fun cancel() {
        if (!isLinking) return
        generation++
        transient = null
        SeamChat.info("Linking cancelled.")
    }

    /**
     * Drop the local token, revoking it server-side first (best effort — an offline player can still
     * unlink; the token then dies of its own expiry).
     */
    fun unlink() {
        generation++
        val wasLinked = SeamApi.isLinked
        SeamApi.client.revokeToken().whenComplete { result, thrown ->
            if (thrown != null || result is ApiResult.Failure) {
                SeamClient.logger.warn("Could not revoke Seam token server-side; clearing it locally anyway")
            }
            ConfigStore.update { it.copy(auth = null) }
            transient = null
            if (wasLinked) SeamChat.info("Seam account unlinked.")
        }
    }

    // ── Flow internals ─────────────────────────────────────────────────────────

    private fun beginPolling(attempt: Int, code: DeviceCodeResponse) {
        val interval = PollPolicy.sanitizeInterval(code.interval)
        val expiresAt = System.currentTimeMillis() + code.expiresIn * 1_000
        transient = State.AwaitingApproval(code.userCode, code.verificationUri, expiresAt)

        announce(code)
        schedulePoll(attempt, code.deviceCode, interval, expiresAt, consecutiveNetworkErrors = 0)
    }

    /**
     * Open the verification page for the player and tell them the code. The browser launch is
     * best-effort (headless setups, exotic desktops), so the chat line still carries a real
     * clickable link (hazard H2 — `ClickEvent.OpenUrl(URI)`) as the fallback.
     */
    private fun announce(code: DeviceCodeResponse) {
        Browser.open(code.verificationUri)
        SeamChat.send(
            Text.literal("Opening ")
                .append(SeamChat.link(code.verificationUri, code.verificationUri))
                .append(Text.literal(" — enter code "))
                .append(SeamChat.code(code.userCode))
                .append(Text.literal(" to link your Seam account.")),
        )
        // Convenience only — a failed clipboard write must not derail the flow.
        onClientThread {
            runCatching { MinecraftClient.getInstance().keyboard.setClipboard(code.userCode) }
                .onSuccess { SeamChat.info("Code copied to your clipboard.") }
        }
    }

    private fun schedulePoll(
        attempt: Int,
        deviceCode: String,
        intervalSeconds: Int,
        expiresAtMillis: Long,
        consecutiveNetworkErrors: Int,
    ) {
        if (attempt != generation) return
        if (System.currentTimeMillis() >= expiresAtMillis) {
            fail(attempt, "The code expired. Start linking again.")
            return
        }
        scheduler.schedule(
            { poll(attempt, deviceCode, intervalSeconds, expiresAtMillis, consecutiveNetworkErrors) },
            intervalSeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun poll(
        attempt: Int,
        deviceCode: String,
        intervalSeconds: Int,
        expiresAtMillis: Long,
        consecutiveNetworkErrors: Int,
    ) {
        if (attempt != generation) return
        SeamApi.client.pollDeviceCode(deviceCode).thenAccept { result ->
            if (attempt != generation) return@thenAccept
            when (result) {
                is ApiResult.Ok -> onLinked(attempt, result.value)

                // A pending approval is an HTTP 400 with an RFC-8628 error code — normal, not a fault.
                is ApiResult.Error -> apply(
                    attempt, deviceCode, expiresAtMillis,
                    decision = PollPolicy.decide(result.code, intervalSeconds),
                    consecutiveNetworkErrors = 0,
                )

                is ApiResult.Failure -> {
                    val errors = consecutiveNetworkErrors + 1
                    SeamClient.logger.debug("Device-code poll failed ({}); attempt {} of {}", result.describe(), errors, PollPolicy.MAX_CONSECUTIVE_NETWORK_ERRORS)
                    apply(
                        attempt, deviceCode, expiresAtMillis,
                        decision = PollPolicy.decideNetworkError(errors, intervalSeconds),
                        consecutiveNetworkErrors = errors,
                    )
                }
            }
        }
    }

    private fun apply(
        attempt: Int,
        deviceCode: String,
        expiresAtMillis: Long,
        decision: PollPolicy.Decision,
        consecutiveNetworkErrors: Int,
    ) = when (decision) {
        is PollPolicy.Decision.Retry ->
            schedulePoll(attempt, deviceCode, decision.intervalSeconds, expiresAtMillis, consecutiveNetworkErrors)
        is PollPolicy.Decision.Stop -> fail(attempt, decision.reason)
    }

    private fun onLinked(attempt: Int, token: PollSuccessResponse) {
        if (attempt != generation) return
        ConfigStore.update {
            it.copy(
                auth = AuthConfig(
                    token = token.accessToken,
                    username = token.username.takeIf { name -> name.isNotBlank() },
                    linkedAt = Instant.now().toString(),
                ),
            )
        }
        transient = null   // state now derives from the stored token
        SeamChat.success("Linked to Seam as ${token.username.ifBlank { "your account" }}.")
    }

    private fun fail(attempt: Int, reason: String) {
        if (attempt != generation) return
        transient = State.Failed(reason)
        SeamChat.error(reason)
    }
}
