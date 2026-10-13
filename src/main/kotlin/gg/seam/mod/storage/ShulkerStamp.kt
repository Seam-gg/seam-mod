package gg.seam.mod.storage

import net.minecraft.block.Block
import net.minecraft.block.ShulkerBoxBlock
import net.minecraft.block.entity.BlockEntity
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.loot.context.LootContext
import net.minecraft.loot.context.LootContextParameters
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos

/**
 * Lets a tagged shulker box's tag follow it when it is picked up (MCO-548). The rule for what that
 * means for the count is [CarriedShulkers]; this file is the part that touches the world.
 *
 * **Nothing in this file may reference `MinecraftClient`** — it loads on dedicated servers.
 *
 * The stamp is `minecraft:custom_data` → `{seam_notebook: {tag_id: <id>}}` on the item. A vanilla
 * component, so the mod still registers nothing and a client without it sees an ordinary shulker
 * box. Left behind on an item after the mod is removed, it does nothing at all.
 *
 * **It is written as the box drops, not ahead of time.** The vanilla shulker box loot table copies
 * only `custom_name`, `container`, `lock` and `container_loot` from the block entity to the dropped
 * item, so a stamp put on the placed box would be lost the moment it was broken in survival. The
 * drop is the one place the stamp can be added that every cause of breaking passes through — a
 * player, an explosion, a piston. (Creative mode is the exception: it builds the drop by hand from
 * the block entity's components and never consults the loot table. Counting stock in creative means
 * nothing, so it is left out rather than hooked.)
 */
object ShulkerStamp {

    private const val ROOT = "seam_notebook"
    private const val TAG_ID = "tag_id"

    /**
     * `LootTableEvents.MODIFY_DROPS` for every loot table; returns at once for anything that is not
     * a shulker box. Runs on the server thread, as block breaking does, so it may ask the reporter.
     *
     * The tag comes from the reporter's list when the box was broken at a tagged position, and
     * otherwise from a stamp the placed box still carries. That second case is a carried box that
     * was set down somewhere untagged and picked up again: a block entity keeps the components it
     * does not itself consume, so the stamp survives placing, and copying it back keeps the box
     * recognisable on the next trip.
     */
    fun onDrops(context: LootContext, drops: List<ItemStack>, taggedShulkerAt: (String, BlockPos) -> Long?) {
        val state = context.get(LootContextParameters.BLOCK_STATE) ?: return
        if (state.block !is ShulkerBoxBlock) return
        val origin = context.get(LootContextParameters.ORIGIN) ?: return

        val dimension = context.world.registryKey.value.toString()
        val tagId = taggedShulkerAt(dimension, BlockPos.ofFloored(origin))
            ?: stampOf(context.get(LootContextParameters.BLOCK_ENTITY))
            ?: return

        drops.filter { Block.getBlockFromItem(it.item) is ShulkerBoxBlock }.forEach { stamp(it, tagId) }
    }

    /**
     * Every online player, and every stamped box on one of them — main inventory, armour and off
     * hand, plus whatever is on the cursor, since a box being moved between slots is still carried.
     *
     * **Not the ender chest**, deliberately: it is per-player storage, which the shared count leaves
     * out. Two copies of one stamp (a creative pick-block) count once; the first found wins, and
     * [CarriedShulkers.resolve] prefers the box at its position over either.
     */
    fun scan(server: MinecraftServer): Sighting {
        val online = mutableSetOf<String>()
        val boxes = mutableMapOf<Long, CarriedBox>()

        for (player in server.playerManager.playerList) {
            val carrier = Carrier(player.uuidAsString, player.name.string)
            online += carrier.uuid

            fun consider(stack: ItemStack) {
                val tagId = stampOf(stack) ?: return
                if (tagId !in boxes) boxes[tagId] = CarriedBox(tagId, carrier, ContainerSweep.contentsOf(stack))
            }

            val inventory = player.inventory
            for (slot in 0 until inventory.size()) consider(inventory.getStack(slot))
            consider(player.currentScreenHandler.cursorStack)
        }
        return Sighting(online, boxes)
    }

    private fun stamp(stack: ItemStack, tagId: Long) =
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack) { nbt ->
            nbt.put(ROOT, NbtCompound().apply { putLong(TAG_ID, tagId) })
        }

    private fun stampOf(stack: ItemStack): Long? {
        if (stack.isEmpty) return null
        return stampOf(stack.get(DataComponentTypes.CUSTOM_DATA))
    }

    private fun stampOf(blockEntity: BlockEntity?): Long? =
        stampOf(blockEntity?.components?.get(DataComponentTypes.CUSTOM_DATA))

    private fun stampOf(data: NbtComponent?): Long? {
        if (data == null || data.isEmpty) return null
        return data.copyNbt().getCompound(ROOT).flatMap { it.getLong(TAG_ID) }.orElse(null)
    }
}
