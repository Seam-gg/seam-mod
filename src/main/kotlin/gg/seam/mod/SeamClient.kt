package gg.seam.mod

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import gg.seam.mod.data.ConfigStore
import gg.seam.mod.data.SeamDataStore
import gg.seam.mod.data.SeamSync
import gg.seam.mod.data.WorldDataStore
import gg.seam.mod.data.WorldKey
import gg.seam.mod.screen.NotebookScreen
import gg.seam.mod.tag.ContainerTagStore
import gg.seam.mod.tag.TagGesture
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * Client entrypoint for the Seam Notebook mod.
 *
 * Phase 0: registers the "open notebook" keybind (default: N) and confirms it fires.
 * Phase 1 will replace the placeholder action with the notebook [net.minecraft.client.gui.screen.Screen].
 *
 * The notebook is opened by a keybind — NOT a custom item — because a client-only mod's
 * registered item is unknown to the multiplayer server we run (registry sync excludes it).
 * See docs/fabric-1.21.11-reference.md §3 (decision D1).
 */
object SeamClient : ClientModInitializer {
    // Not plain "seam": an unrelated mod already owns that id on Modrinth, and two mods sharing an
    // id is a hard load failure for anyone running both. Namespaces resources and the log channel.
    const val MOD_ID = "seam_notebook"
    val logger = LoggerFactory.getLogger(MOD_ID)

    private lateinit var openNotebookKey: KeyBinding
    private lateinit var tagContainerKey: KeyBinding

    override fun onInitializeClient() {
        openNotebookKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.seam_notebook.open_notebook",   // translation key (assets/seam_notebook/lang)
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                // 1.21.x: category is a KeyBinding.Category record, not a String.
                KeyBinding.Category.create(Identifier.of(MOD_ID, "general")),
            ),
        )

        // The second way into the tag picker (MCO-261). The gesture — empty hand, sneak,
        // right-click — is the primary one; this exists because a builder's hotbar is rarely empty
        // and emptying a hand to tag a chest is a silly thing to have to do.
        tagContainerKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.seam_notebook.tag_container",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "general")),
            ),
        )

        TagGesture.register()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openNotebookKey.wasPressed()) {
                client.setScreen(NotebookScreen())
            }
            while (tagContainerKey.wasPressed()) {
                TagGesture.openForCrosshairTarget(client)
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
                // Flush first: anything queued from a previous, offline session goes out before we
                // pull, so the values we then render already include it.
                WorldDataStore.loadAsync(key).thenRun {
                    SeamSync.flush().thenRun { SeamDataStore.refresh() }
                }
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            WorldDataStore.unload()
            // Drop the cache too, so a second world never renders the first one's projects.
            SeamDataStore.clear()
            ContainerTagStore.clear()
            SeamSync.clear()
        }

        logger.info("Seam Notebook (client) initialized")
    }
}
