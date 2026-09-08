package gg.seam.mod.tag

import gg.seam.mod.screen.ContainerTagScreen
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult

/**
 * How a player tags a container (MCO-261): **empty main hand + sneak + right-click**, or the tag
 * keybind while looking at one.
 *
 * ## Why the empty hand is part of the gesture
 *
 * Sneak + right-clicking a container is a genuine vanilla no-op — sneaking suppresses the container
 * GUI so you can place blocks against it, and an empty hand places nothing. Intercepting *that*
 * costs nothing.
 *
 * Intercepting sneak + right-click regardless of what is held would not: placing a block against a
 * chest while sneaking is something builders do constantly, and cancelling it would make the mod
 * feel broken in a way nobody would connect to a tagging feature. The issue says "sneak +
 * right-click"; the empty hand is the refinement that makes it safe.
 *
 * ## Client-side only
 *
 * Registered from the client entrypoint, so this callback never runs on a dedicated server. The tag
 * is posted to mc-org with the **player's own token** — tagging records who did it, and the
 * reporter's world-scoped token deliberately cannot do it. Nothing passes between the mod's two
 * halves here, as everywhere else.
 */
object TagGesture {

    fun register() {
        UseBlockCallback.EVENT.register { player, world, hand, hit ->
            // ⚠ Fabric hooks this in BEFORE the spectator check, so game mode is ours to test.
            if (hand != Hand.MAIN_HAND) return@register ActionResult.PASS
            if (!player.isSneaking) return@register ActionResult.PASS
            if (!player.mainHandStack.isEmpty) return@register ActionResult.PASS
            if (player.isSpectator) return@register ActionResult.PASS

            val target = ContainerTarget.at(world, hit.blockPos) ?: return@register ActionResult.PASS

            open(target)
            // FAIL, not SUCCESS: SUCCESS would also send an interaction packet to the server, which
            // on a server without the mod is an ordinary use-block and opens the chest behind our
            // screen. FAIL cancels locally and sends nothing — the whole interaction stays client
            // side, which is what a screen the server knows nothing about requires.
            ActionResult.FAIL
        }
    }

    /**
     * Open the picker for whatever the player is looking at, or do nothing.
     *
     * The keybind's path in. Silence is the right answer when the crosshair is not on a container:
     * a keybind that scolds you for pressing it is worse than one that does nothing.
     */
    fun openForCrosshairTarget(client: MinecraftClient) {
        val world = client.world ?: return
        val hit = client.crosshairTarget as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val target = ContainerTarget.at(world, hit.blockPos) ?: return
        open(target)
    }

    private fun open(target: ContainerTarget) {
        val client = MinecraftClient.getInstance()
        client.setScreen(ContainerTagScreen(target, client.currentScreen))
    }
}
