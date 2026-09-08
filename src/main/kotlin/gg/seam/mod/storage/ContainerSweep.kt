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
     * Reads one **group** — the set of tags naming a single physical inventory — **on the server
     * thread**, because world access is not thread-safe.
     *
     * Returns a reading per member it can speak for, and simply omits the rest. An omitted
     * container keeps whatever the webapp already holds for it, which is the design's load-bearing
     * idea: a container in an unloaded chunk **cannot have changed**, because nobody was there to
     * change it, so its last reading is still correct and overwriting it would be the bug.
     *
     * Why a group and not a position: a joined double chest is two tag rows sharing a `group_key`,
     * and `ChestBlock.getInventory(..., true)` returns the **combined** inventory from *either*
     * half — so reading both would double every count. One member holds the counts; the others are
     * reported as seen and empty.
     *
     * Which member holds them is decided **per pass, from what is actually there**, not once from
     * the coordinates. If it were fixed, breaking the lower half would report the pair as missing
     * while a perfectly readable chest stood next to it; and when the holder's chunk unloaded while
     * its sibling's stayed loaded — a double chest straddling a chunk border, roughly one pair in
     * eight — the counts would move to the sibling while the holder kept its own copy, and the
     * webapp would count the pair twice. Hence: the first member that is genuinely readable holds
     * the counts, and every other member of a group with a holder is zeroed in the same push.
     *
     * A group means "one inventory", which is what the webapp's `group_key` promises and what the
     * tagging gesture writes. Grouping two unrelated barrels by hand would report one and zero the
     * other; the webapp's adjacency check on `group_key` is what bounds that.
     *
     * [interest] bounds what is reported. Without it the push would carry an inventory of somebody's
     * junk drawer and the webapp would quietly become a whole-world item census.
     */
    fun readGroup(
        server: MinecraftServer,
        members: List<TaggedContainer>,
        interest: Set<String>,
    ): Map<Long, Reading> {
        if (members.isEmpty()) return emptyMap()

        val world = server.getWorld(members.first().dimensionKey())
            ?: return members.associate { it.id to Reading(it.id, STATE_MISSING, emptyMap()) }

        // Look at every member once. `getBlockState` on an unloaded chunk would load it
        // synchronously, so the chunk check is not an optimisation — it is the thing that keeps a
        // sweep from dragging terrain in off a tag nobody has visited.
        var holder: TaggedContainer? = null
        var holderInventory: Inventory? = null
        val gone = mutableListOf<TaggedContainer>()
        val unloaded = mutableListOf<TaggedContainer>()

        for (member in members) {
            val pos = BlockPos(member.x, member.y, member.z)
            if (!world.isChunkLoaded(ChunkPos.toLong(pos))) {
                unloaded += member
                continue
            }
            val inventory = inventoryAt(world, pos)
            when {
                inventory == null -> gone += member
                holder == null -> {
                    holder = member
                    holderInventory = inventory
                }
            }
        }

        // Nothing readable: say only what was actually looked at. With every member in an unloaded
        // chunk that is nothing at all, which is the correct amount to say.
        if (holder == null) {
            return gone.associate { it.id to Reading(it.id, STATE_MISSING, emptyMap()) }
        }

        val readings = mutableMapOf<Long, Reading>()
        readings[holder.id] = Reading(holder.id, STATE_OK, count(holderInventory!!, interest))
        for (member in members) {
            if (member.id == holder.id) continue
            readings[member.id] = when (member) {
                // A member whose block is gone is missing; every other member of a group that has a
                // holder contributes nothing *by construction*, unloaded chunk or not — the holder
                // is carrying the whole inventory. Saying so explicitly is what stops the pair being
                // counted twice when the holder changes between passes.
                in gone -> Reading(member.id, STATE_MISSING, emptyMap())
                else -> Reading(member.id, STATE_OK, emptyMap())
            }
        }
        return readings
    }

    /**
     * The inventory at a position, or null when the block is gone or is not a container.
     *
     * For a chest this asks for the **combined** inventory, which is what
     * `ChestBlock.getInventory(..., true)` returns from *either* half of a joined pair.
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
     * **An empty [interest] reports nothing, not everything.** The failure modes are not
     * symmetrical: a project with no items of interest reporting zero looks broken and is harmless,
     * while the same case reporting everything would quietly ship the webapp a whole-world item
     * census off one mis-shaped response. The reporter says the first out loud instead — see
     * `ReporterService.applyTags`.
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
            if (id !in interest) return
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
     * Splits tags into groups, one group per physical inventory.
     *
     * Members are ordered by `(x, z, id)` and the groups themselves by their key, so a pass visits
     * the same containers in the same order every time — the sweep's cursor depends on that, and so
     * does which member ends up holding the counts when several are readable.
     *
     * A blank `group_key` falls back to the position, so an untagged-by-the-mod row cannot swallow
     * unrelated containers into one group.
     */
    fun groupsOf(tags: List<TaggedContainer>): List<List<TaggedContainer>> =
        tags.groupBy { it.groupKey.ifBlank { "${it.x},${it.y},${it.z}" } }
            .toSortedMap()
            .map { (_, group) -> group.sortedWith(compareBy({ it.x }, { it.z }, { it.id })) }
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
