package gg.seam.mod

import gg.seam.mod.api.SeamApiClient
import gg.seam.mod.storage.ReporterConfig
import gg.seam.mod.storage.ReporterService
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import org.slf4j.LoggerFactory

/**
 * Server entrypoint for the Seam Notebook mod (MCO-534).
 *
 * This is the `main` entrypoint, not `server`, and that is deliberate: `main` runs on a dedicated
 * server **and in singleplayer's integrated server**, which makes SP and MP one code path rather
 * than two implementations. Everything the reporter does is therefore exercisable in singleplayer
 * against a local webapp before a jar goes near a real server.
 *
 * The two halves of this mod never speak to each other. No custom packets, no blocks, no items — so
 * a client without the mod can join a server with it and vice versa, and there is no compatibility
 * matrix to maintain. They meet in mc-org.
 *
 * ⚠ Everything under `gg.seam.mod.storage` must stay free of `MinecraftClient`, or it will not
 * class-load here.
 */
object SeamServer : ModInitializer {

    private val log = LoggerFactory.getLogger("${SeamClient.MOD_ID}-server")

    private var service: ReporterService? = null

    override fun onInitialize() {
        val config = ReporterConfig.load(log = log)

        // An unconfigured server is a SUPPORTED state, not an error. Someone installing the jar for
        // the client half alone should see one line explaining how to turn the reporter on, and
        // then never hear from it again — never a warning per tick.
        if (config == null || !config.isConfigured) {
            log.info(
                "Seam reporter is off. To turn it on, put api_base_url, seam_world_id and a " +
                    "reporter token (world settings → Connected server) in {}",
                ReporterConfig.path(),
            )
            return
        }

        log.info(
            "Seam reporter is on for world {} at {} (sweeping every {}s, {} containers per tick)",
            config.seamWorldId, config.apiBaseUrl, config.sweepSeconds, config.readsPerTick,
        )

        val api = SeamApiClient(
            baseUrl = { config.apiBaseUrl },
            token = { config.token },
            userAgent = "SeamNotebook reporter/${ReporterService.REPORTER_VERSION}",
        )

        ServerLifecycleEvents.SERVER_STARTED.register {
            service = ReporterService(config, api, log)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            service = null
        }

        // END_SERVER_TICK, so a read sees the tick's completed state rather than a half-applied one.
        // The service reads a bounded number of containers here and does its HTTP off-thread.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            service?.tick(server)
        }
    }
}
