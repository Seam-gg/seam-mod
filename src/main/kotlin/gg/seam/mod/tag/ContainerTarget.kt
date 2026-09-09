package gg.seam.mod.tag

import gg.seam.mod.api.ContainerTagRequest
import gg.seam.mod.storage.ContainerKind
import net.minecraft.block.ChestBlock
import net.minecraft.block.enums.ChestType
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/** A position, as the webapp's tag rows and group keys spell one. */
data class TagPos(val x: Int, val y: Int, val z: Int) {
    /** The `group_key` form. The webapp accepts a container's own position or an adjacent one. */
    override fun toString(): String = "$x,$y,$z"
}

/**
 * One physical inventory the player can tag — a single container, or **both halves** of a joined
 * chest (MCO-261).
 *
 * A joined pair is two tag rows sharing one `group_key`, deliberately: either half can carry a
 * label and breaking one does not orphan the tag. The sweep reads such a group once and reports the
 * rest as empty, so the count is not doubled — but only if both rows are posted with the *same*
 * key, which is this type's whole job. Tagging one half of a pair and leaving the other untagged
 * would work too; tagging both **without** a shared key is the mistake that doubles every number,
 * and making the pair a single object is what stops a caller doing it by accident.
 */
data class ContainerTarget(
    val kind: String,
    val dimension: String,
    val positions: List<TagPos>,
) {
    init {
        require(positions.isNotEmpty()) { "a container target names at least one position" }
    }

    /**
     * The shared identity of this inventory: the lowest `(x, z)` of its positions.
     *
     * Stable and order-independent, so tagging the left half and tagging the right half produce the
     * same key — otherwise the two halves would land in different groups depending on which one the
     * player happened to click, and the pair would be counted twice.
     */
    val groupKey: String get() = positions.minWith(compareBy({ it.x }, { it.z })).toString()

    /** True when this is a joined pair rather than a lone container. */
    val isPair: Boolean get() = positions.size > 1

    /** One request per row. Posting them all is what tagging this inventory means. */
    fun requests(projectId: Int): List<ContainerTagRequest> = positions.map { pos ->
        ContainerTagRequest(
            dimension = dimension,
            x = pos.x, y = pos.y, z = pos.z,
            projectId = projectId,
            kind = kind,
            groupKey = groupKey,
        )
    }

    companion object {
        /**
         * The taggable inventory at [pos], or null when that block is not one.
         *
         * Null is the "leave it alone" answer and the gesture depends on it: everything that is not
         * a whitelisted container must fall through to vanilla behaviour untouched.
         *
         * ⚠ `ChestBlock.getFacing(state)` returns the direction to the **other half** and is only
         * meaningful when `CHEST_TYPE` is not `SINGLE` — for a lone chest it returns a direction
         * anyway, so reading it unguarded would invent a second position out of thin air and tag
         * whatever happened to be next door. Verified against the 1.21.11 jar; see
         * `docs/fabric-1.21.11-reference.md` §3.
         */
        fun at(world: World, pos: BlockPos): ContainerTarget? {
            val state = world.getBlockState(pos)
            val kind = ContainerKind.of(state.block) ?: return null
            val dimension = world.registryKey.value.toString()

            val positions = mutableListOf(TagPos(pos.x, pos.y, pos.z))
            if (state.block is ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                val other = pos.offset(ChestBlock.getFacing(state))
                // Only if the neighbour really is the matching half. A half whose partner was just
                // broken still reports LEFT/RIGHT for a tick or two, and tagging the air beside it
                // would leave a permanent `missing` row.
                if (ContainerKind.of(world.getBlockState(other).block) == kind) {
                    positions += TagPos(other.x, other.y, other.z)
                }
            }
            return ContainerTarget(kind, dimension, positions)
        }
    }
}
