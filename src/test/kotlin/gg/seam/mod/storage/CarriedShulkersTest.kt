package gg.seam.mod.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule that keeps a carried shulker box counting (MCO-548). Pure, so every row of it is pinned
 * here; the stamp and the inventory scan touch a world and are `runServer` territory.
 */
class CarriedShulkersTest {

    private val even = Carrier("uuid-even", "Even")
    private val iron = "minecraft:iron_ingot"
    private val dirt = "minecraft:dirt"

    private fun sighting(vararg boxes: CarriedBox, online: Set<String> = boxes.map { it.carrier.uuid }.toSet()) =
        Sighting(online, boxes.associateBy { it.tagId })

    private fun resolve(
        position: ContainerSweep.Reading? = null,
        sighting: Sighting = Sighting.NONE,
        lastCarrier: Carrier? = null,
    ) = CarriedShulkers.resolve(1, position, sighting, lastCarrier, interest = setOf(iron))

    @Test
    fun `a box picked up still counts, from what is in it now, and says who has it`() {
        val reading = resolve(
            position = ContainerSweep.Reading(1, ContainerSweep.STATE_MISSING, emptyMap()),
            sighting = sighting(CarriedBox(1, even, mapOf(iron to 12L, dirt to 64L))),
        )

        // The bug was this reading zero while the box was on its way to the build.
        assertEquals(ContainerSweep.Reading(1, ContainerSweep.STATE_IN_TRANSIT, mapOf(iron to 12L), even), reading)
    }

    @Test
    fun `a carried box counts even though its tagged position is in an unloaded chunk`() {
        // The usual case, not an edge one: the carrier has walked away from the storage area, so
        // the position reads as nothing at all.
        val reading = resolve(position = null, sighting = sighting(CarriedBox(1, even, mapOf(iron to 3L))))

        assertEquals(ContainerSweep.STATE_IN_TRANSIT, reading?.state)
    }

    @Test
    fun `a box back at its position is read there, even if a copy of the stamp is on a player`() {
        val atPosition = ContainerSweep.Reading(1, ContainerSweep.STATE_OK, mapOf(iron to 5L))

        val reading = resolve(position = atPosition, sighting = sighting(CarriedBox(1, even, mapOf(iron to 5L))))

        assertEquals(atPosition, reading)
    }

    @Test
    fun `a carrier who logs off keeps the box counting, because nobody can change it meanwhile`() {
        val reading = resolve(
            position = ContainerSweep.Reading(1, ContainerSweep.STATE_MISSING, emptyMap()),
            sighting = Sighting(online = emptySet(), boxes = emptyMap()),
            lastCarrier = even,
        )

        // Null is "say nothing", which keeps the in_transit reading the webapp already has.
        assertNull(reading)
    }

    @Test
    fun `a carrier who is online without the box has put it somewhere the sweep cannot see`() {
        val reading = resolve(
            position = null,
            sighting = Sighting(online = setOf(even.uuid), boxes = emptyMap()),
            lastCarrier = even,
        )

        assertEquals(ContainerSweep.Reading(1, ContainerSweep.STATE_MISSING, emptyMap()), reading)
    }

    @Test
    fun `a box handed to another player follows the new carrier`() {
        val other = Carrier("uuid-other", "Other")

        val reading = resolve(
            sighting = sighting(CarriedBox(1, other, mapOf(iron to 1L)), online = setOf(other.uuid)),
            lastCarrier = even,
        )

        assertEquals(other, reading?.carrier)
    }

    @Test
    fun `a box that was never carried is whatever its position says`() {
        val missing = ContainerSweep.Reading(1, ContainerSweep.STATE_MISSING, emptyMap())

        assertEquals(missing, resolve(position = missing))
        assertNull(resolve(position = null))
    }

    @Test
    fun `a carried box reports nothing for a project with nothing to measure, as a placed one does`() {
        val reading = CarriedShulkers.resolve(
            1, null, sighting(CarriedBox(1, even, mapOf(iron to 12L))), null, interest = emptySet(),
        )

        assertEquals(emptyMap(), reading?.counts)
    }
}
