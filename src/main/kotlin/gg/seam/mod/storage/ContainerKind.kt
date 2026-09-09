package gg.seam.mod.storage

import net.minecraft.block.BarrelBlock
import net.minecraft.block.Block
import net.minecraft.block.ChestBlock
import net.minecraft.block.DispenserBlock
import net.minecraft.block.DropperBlock
import net.minecraft.block.HopperBlock
import net.minecraft.block.ShulkerBoxBlock
import net.minecraft.block.TrappedChestBlock

/**
 * Which blocks may carry a container tag, and what the webapp calls each one.
 *
 * **One whitelist, two users.** The sweep needs it so a tag whose chest has become a furnace stops
 * counting ([ContainerSweep]); the tagging gesture needs it so sneak + right-click falls through to
 * vanilla on everything else (MCO-261). They must agree, and they both mirror a third thing —
 * `container_tags.kind`'s CHECK constraint in mc-org. Change all three together.
 *
 * **Not "anything that is an `Inventory`".** A furnace is one, and counting its fuel and its input
 * as project stock is nobody's intent. Nor is an ender chest: it is per-player, so it cannot mean
 * anything in a shared count (decision D1). `EnderChestBlock` extends `AbstractChestBlock`
 * directly rather than `ChestBlock`, so `is ChestBlock` excludes it with no special case — verified
 * against the 1.21.11 jar, not assumed.
 *
 * **This file must stay free of `MinecraftClient`.** It is in the shared package precisely so both
 * halves can use it, which means it loads on a dedicated server.
 */
object ContainerKind {

    const val CHEST = "chest"
    const val TRAPPED_CHEST = "trapped_chest"
    const val BARREL = "barrel"
    const val SHULKER_BOX = "shulker_box"
    const val HOPPER = "hopper"
    const val DROPPER = "dropper"
    const val DISPENSER = "dispenser"

    /**
     * The webapp's `kind` for [block], or null when it is not taggable.
     *
     * Order is load-bearing: `TrappedChestBlock` is a `ChestBlock` and `DropperBlock` is a
     * `DispenserBlock`, so the subclasses have to be asked about first or a trapped chest would
     * report itself as a plain chest and the webapp would store the wrong kind.
     */
    fun of(block: Block): String? = when (block) {
        is TrappedChestBlock -> TRAPPED_CHEST
        is ChestBlock -> CHEST
        is BarrelBlock -> BARREL
        is ShulkerBoxBlock -> SHULKER_BOX
        is HopperBlock -> HOPPER
        is DropperBlock -> DROPPER
        is DispenserBlock -> DISPENSER
        else -> null
    }

    fun isTaggable(block: Block): Boolean = of(block) != null
}
