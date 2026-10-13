package gg.seam.mod.storage

/**
 * Which tagged shulker boxes are being carried, and what that means for their count (MCO-548).
 *
 * A shulker box is the one container that keeps its inventory when it is picked up: the contents
 * travel with the item. So for a shulker box, "nothing at the tagged position" usually means
 * "somebody is walking the stock to the build", not "the stock is gone", and zeroing it would be
 * wrong at exactly the moment a player is most likely to be looking at the count.
 *
 * The tag cannot follow the item by itself — it is keyed on a position. [ShulkerStamp] makes it
 * follow: the dropped box is stamped with its tag id, and each pass looks for stamped boxes in
 * online players' inventories. This file is the rule that turns those sightings into a state, kept
 * free of Minecraft types so it can be tested without a world.
 *
 * **Only tagged shulker boxes on a player count.** Nothing else in a player's inventory does, and
 * neither does a stamped box in an ender chest: those are per-player storage, which the shared
 * count deliberately leaves out.
 */
object CarriedShulkers {

    /**
     * The state of one tagged shulker box, or null for "say nothing, keep what the webapp has".
     *
     * - **At its position** ([position] is `ok`) — the position is the truth, even if a copy of the
     *   stamp is also on somebody (a creative-mode pick-block copies components).
     * - **On an online player** — `in_transit`, counted from the box's **current** contents, so items
     *   taken out on the way stop counting. This holds whether or not the tagged position's chunk is
     *   loaded, which is the common case: the carrier has walked away from it.
     * - **Last seen on a player who is now offline** — nothing. An offline player's inventory cannot
     *   change, for the same reason a container in an unloaded chunk cannot: nobody is there to
     *   change it. Its last reading is still correct.
     * - **Last seen on a player who is online and no longer has it** — `missing`. It was placed
     *   somewhere untagged, put in a chest, dropped or destroyed; whichever it was, the sweep can no
     *   longer see the stock, and claiming it would be the lie this rule exists to avoid. If it was
     *   put back at its own position while that chunk was unloaded, the next read there corrects it.
     * - **Never carried** — whatever the position said, which is `missing`, or nothing for an
     *   unloaded chunk.
     *
     * [lastCarrier] is who had it at the last `in_transit` reading — from this reporter's own memory,
     * or from the webapp's copy after a restart, since "carrier offline" and "carrier online without
     * it" lead to opposite answers and a restart must not confuse them.
     */
    fun resolve(
        tagId: Long,
        position: ContainerSweep.Reading?,
        sighting: Sighting,
        lastCarrier: Carrier?,
        interest: Set<String>,
    ): ContainerSweep.Reading? {
        if (position?.state == ContainerSweep.STATE_OK) return position

        sighting.boxes[tagId]?.let { box ->
            return ContainerSweep.Reading(
                tagId = tagId,
                state = ContainerSweep.STATE_IN_TRANSIT,
                counts = box.counts.filterKeys { it in interest },
                carrier = box.carrier,
            )
        }

        if (lastCarrier != null) {
            if (lastCarrier.uuid !in sighting.online) return null
            return ContainerSweep.Reading(tagId, ContainerSweep.STATE_MISSING, emptyMap())
        }

        return position
    }
}

/** A player carrying a tagged shulker box. [name] is for people looking for it; [uuid] is identity. */
data class Carrier(val uuid: String, val name: String)

/** A stamped shulker box found on a player, with everything in it — interest is applied later. */
data class CarriedBox(val tagId: Long, val carrier: Carrier, val counts: Map<String, Long>)

/** One look at who is online and which stamped boxes they carry. */
data class Sighting(val online: Set<String>, val boxes: Map<Long, CarriedBox>) {
    companion object {
        val NONE = Sighting(emptySet(), emptyMap())
    }
}
