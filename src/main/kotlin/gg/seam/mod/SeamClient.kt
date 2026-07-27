package gg.seam.mod

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import gg.seam.mod.data.ConfigStore
import gg.seam.mod.data.SeamDataStore
import gg.seam.mod.data.WorldDataStore
import gg.seam.mod.data.WorldKey
import gg.seam.mod.screen.NotebookScreen
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * Client entrypoint for the Seam Companion mod.
 *
 * Phase 0: registers the "open notebook" keybind (default: N) and confirms it fires.
 * Phase 1 will replace the placeholder action with the notebook [net.minecraft.client.gui.screen.Screen].
 *
 * The notebook is opened by a keybind — NOT a custom item — because a client-only mod's
 * registered item is unknown to the multiplayer server we run (registry sync excludes it).
 * See docs/fabric-1.21.11-reference.md §3 (decision D1).
 */
object SeamClient : ClientModInitializer {
    const val MOD_ID = "seam"
    val logger = LoggerFactory.getLogger(MOD_ID)

    private lateinit var openNotebookKey: KeyBinding

    override fun onInitializeClient() {
        openNotebookKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.seam.open_notebook",   // translation key (assets/seam/lang)
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                // 1.21.x: category is a KeyBinding.Category record, not a String.
                KeyBinding.Category.create(Identifier.of(MOD_ID, "general")),
            ),
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openNotebookKey.wasPressed()) {
                client.setScreen(NotebookScreen())
            }
        }

        // Persistence (MCO-258): load global config once; bind/unbind per-world data on connect.
        ConfigStore.loadAsync()

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            val key = WorldKey.forCurrent(client)
            if (key == null) {
                logger.warn("Joined a world but could not resolve a WorldKey; per-world data disabled")
            } else {
                // Pull only once the binding is on disk — refresh() reads seamWorldId from it.
                WorldDataStore.loadAsync(key).thenRun { SeamDataStore.refresh() }
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            WorldDataStore.unload()
            // Drop the cache too, so a second world never renders the first one's projects.
            SeamDataStore.clear()
        }

        logger.info("Seam Companion (client) initialized")
    }
}
