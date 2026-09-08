package gg.seam.mod.storage

import net.minecraft.block.ChestBlock
import net.minecraft.component.DataComponentTypes
import net.minecraft.inventory.Inventory
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos

/**
 * Reads tagged containers (MCO-260).
 *
 * **Nothing in this file may reference `MinecraftClient`** — it runs from the `main` entrypoint,
 * which loads on dedicated servers where that class does not exist.
 *
 * Every API used here is verified against 1.21.11 in `docs/fabric-1.21.11-reference.md` §9, not
 * against the docs site (hazard H10).
 */
object ContainerSweep {

    /** What one container looked like when the sweep reached it. */
    data class Reading(
        val tagId: Long,
        val state: String,
        val counts: Map<String, Long>,
    )

    const val STATE_OK = "ok"
    const val STATE_UNREADABLE = "unreadable"
    const val STATE_MISSING = "missing"

    /**
     * Reads one tagged position **on the server thread** — world access is not thread-safe, so the
     * caller must already be there.
     *
     * Returns null when the container's chunk is not loaded. That is not a failure and not a gap:
     * a container in an unloaded chunk **cannot have changed**, because nobody was there to change
     * it, so its last reading is still correct and the right move is to report nothing rather than
     * to overwrite it. This is the observation the whole design rests on.
     *
     * [interest] bounds what is reported. Without it the push would carry an inventory of somebody's
     * junk drawer and the webapp would quietly become a whole-world item census.
     */
    fun read(server: MinecraftServer, tag: TaggedContainer, interest: Set<String>): Reading? {
        val world = server.getWorld(tag.dimensionKey()) ?: return Reading(tag.id, STATE_MISSING, emptyMap())
        val pos = BlockPos(tag.x, tag.y, tag.z)

        if (!world.isChunkLoaded(ChunkPos.toLong(pos))) return null

        val inventory = inventoryAt(world, pos)
            ?: return Reading(tag.id, STATE_MISSING, emptyMap())

        return Reading(tag.id, STATE_OK, count(inventory, interest))
    }

    /**
     * The inventory at a position, or null when the block is gone or is not a container.
     *
     * For a chest this asks for the **combined** inventory, which is what
     * `ChestBlock.getInventory(..., true)` returns from *either* half of a joined pair — so reading
     * both halves would double-count everything. The caller dedupes by group before getting here;
     * this function's job is only to return the whole inventory a player would see on opening it.
     */
    private fun inventoryAt(world: ServerWorld, pos: BlockPos): Inventory? {
        val state = world.getBlockState(pos)
        val block = state.block
        if (block is ChestBlock) {
            // `false` would ignore a blocked chest (an ocelot or a solid block above it). The stock
            // is in there either way, so read it regardless of whether a player could open it.
            return ChestBlock.getInventory(block, state, world, pos, true)
        }
        return world.getBlockEntity(pos) as? Inventory
    }

    /**
     * Sums an inventory by item id, keeping only [interest].
     *
     * **Looks one level inside shulker boxes.** A stack carrying `DataComponentTypes.CONTAINER`
     * contributes its own contents too — without this, a base that stores in shulkers reports
     * near-zero and the whole feature reads as broken. One level is enough: vanilla shulkers cannot
     * nest.
     *
     * Component-bearing items collapse to their item id, so five differently-enchanted books read
     * as five books. That is correct for the question being asked ("do we have enough iron") and
     * wrong for anything about specific items; accepted deliberately.
     */
    private fun count(inventory: Inventory, interest: Set<String>): Map<String, Long> {
        val counts = mutableMapOf<String, Long>()

        fun add(id: String, n: Int) {
            if (n <= 0) return
            if (interest.isNotEmpty() && id !in interest) return
            counts[id] = (counts[id] ?: 0L) + n.toLong()
        }

        for (slot in 0 until inventory.size()) {
            val stack = inventory.getStack(slot)
            if (stack.isEmpty) continue
            add(idOf(stack.item), stack.count)

            stack.get(DataComponentTypes.CONTAINER)?.streamNonEmpty()?.forEach { nested ->
                if (!nested.isEmpty) add(idOf(nested.item), nested.count)
            }
        }
        return counts
    }

    private fun idOf(item: net.minecraft.item.Item): String = Registries.ITEM.getId(item).toString()

    /**
     * One container per physical inventory.
     *
     * A joined double chest is two tag rows sharing a `group_key`, deliberately: either half can
     * render a label, and breaking one half does not orphan the tag. But `ChestBlock.getInventory`
     * returns the *combined* inventory from either half, so reading both would count everything
     * twice. Exactly one tag per group is read; the rest are reported as seen but empty, so they do
     * not linger as "unreadable" in world settings while contributing nothing to the total.
     *
     * The canonical half is the lowest `(x, z)` — an arbitrary but stable choice, so the same half
     * is picked every sweep and the contents do not migrate between rows.
     */
    fun canonicalByGroup(tags: List<TaggedContainer>): Map<String, TaggedContainer> =
        tags.groupBy { it.groupKey.ifBlank { "${it.x},${it.y},${it.z}" } }
            .mapValues { (_, group) -> group.minWith(compareBy({ it.x }, { it.z }, { it.id })) }
}

/**
 * A tagged container as the reporter needs it — the fields of the webapp's `ContainerTagDto` that
 * the sweep actually uses, kept as a plain type so this package depends on no wire model.
 */
data class TaggedContainer(
    val id: Long,
    val projectId: Int,
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val groupKey: String,
) {
    fun dimensionKey(): net.minecraft.registry.RegistryKey<net.minecraft.world.World> =
        net.minecraft.registry.RegistryKey.of(
            net.minecraft.registry.RegistryKeys.WORLD,
            Identifier.tryParse(dimension) ?: Identifier.of("minecraft", "overworld"),
        )
}
