package gg.seam.mod

import gg.seam.mod.storage.Reporter
import gg.seam.mod.storage.ReporterConfig
import gg.seam.mod.storage.SeamCommand
import net.fabricmc.api.ModInitializer
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

    override fun onInitialize() {
        SeamCommand.register(log)

        val config = ReporterConfig.load(log = log)

        // An unconfigured server is a SUPPORTED state, not an error. One line, pointing at the
        // command that fixes it — not at a JSON file the operator would have to write by hand and
        // restart for. Then silence: never a warning per tick.
        if (config == null || !config.isConfigured) {
            log.info("Seam reporter is off. Turn it on with:  /seam connect <world_id> <token>")
            log.info("  Get a token from the webapp: world settings → Connected server.")
            log.info("  Run it from this console — a command typed in-game lands in the server log.")
        } else {
            Reporter.start(config, log)
        }

        // END_SERVER_TICK, so a read sees the tick's completed state rather than a half-applied one.
        // The reporter reads a bounded number of containers here and does its HTTP off-thread; this
        // is a no-op while it is off.
        ServerTickEvents.END_SERVER_TICK.register { server -> Reporter.tick(server) }
    }
}
