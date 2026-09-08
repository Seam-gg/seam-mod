package gg.seam.mod.storage

import gg.seam.mod.api.SeamApiClient
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger

/**
 * Holds the running reporter, so it can be turned on and off while the server is up (MCO-534).
 *
 * The config is not only read at boot: `/seam connect` writes it and starts the reporter without a
 * restart, which is the difference between "edit this JSON and restart your server" and something a
 * person can actually do.
 *
 * **No `MinecraftClient` in this package** — it loads on dedicated servers.
 */
object Reporter {

    private var service: ReporterService? = null
    private var current: ReporterConfig? = null

    val isRunning: Boolean get() = service != null
    val config: ReporterConfig? get() = current

    /**
     * What the reporter has actually managed to do, for `/seam status`. Null while it is off.
     *
     * Read on the server thread — which is where commands run — because that is the only thread
     * allowed to touch [ReporterService]'s state.
     */
    fun status(): ReporterService.Status? = service?.status()

    /** Starts (or restarts) the reporter for [config]. Returns false when the config cannot report. */
    @Synchronized
    fun start(config: ReporterConfig, log: Logger): Boolean {
        if (!config.isConfigured) return false
        val api = SeamApiClient(
            baseUrl = { config.apiBaseUrl },
            token = { config.token },
            userAgent = "SeamNotebook reporter/${ReporterService.REPORTER_VERSION}",
        )
        current = config
        service = ReporterService(config, api, log)
        log.info(
            "Seam reporter is on for world {} at {} (sweeping every {}s, {} containers per tick)",
            config.seamWorldId, config.apiBaseUrl, config.sweepSeconds, config.readsPerTick,
        )
        return true
    }

    @Synchronized
    fun stop() {
        service = null
        current = null
    }

    /** Called every server tick, on the server thread. No-op while the reporter is off. */
    fun tick(server: MinecraftServer) {
        service?.tick(server)
    }
}
