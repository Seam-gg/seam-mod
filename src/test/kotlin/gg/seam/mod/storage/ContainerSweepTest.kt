package gg.seam.mod.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The double-chest dedupe (MCO-260).
 *
 * Only the pure part is testable here — anything touching a world needs a running server, which is
 * `runClient` / a real server's job. But this is the part that would silently double every count,
 * so it is worth pinning on its own.
 */
class ContainerSweepTest {

    private fun tag(id: Long, x: Int, z: Int, groupKey: String, projectId: Int = 1) =
        TaggedContainer(
            id = id,
            projectId = projectId,
            dimension = "minecraft:overworld",
            x = x, y = 64, z = z,
            groupKey = groupKey,
        )

    @Test
    fun `a joined double chest is read once, not twice`() {
        val lower = tag(1, x = 4, z = 4, groupKey = "4,64,4")
        val upper = tag(2, x = 5, z = 4, groupKey = "4,64,4")

        val canonical = ContainerSweep.canonicalByGroup(listOf(lower, upper))

        // ChestBlock.getInventory(..., true) returns the COMBINED inventory from either half, so
        // reading both would count everything in the pair twice.
        assertEquals(1, canonical.size)
        assertEquals(1L, canonical.getValue("4,64,4").id, "the lower (x,z) half is canonical")
    }

    @Test
    fun `the canonical half is stable, so contents do not migrate between rows`() {
        val lower = tag(1, x = 4, z = 4, groupKey = "4,64,4")
        val upper = tag(2, x = 5, z = 4, groupKey = "4,64,4")

        val first = ContainerSweep.canonicalByGroup(listOf(lower, upper))
        val reversed = ContainerSweep.canonicalByGroup(listOf(upper, lower))

        // Input order must not decide it — otherwise the pair's contents would hop between the two
        // tag rows from sweep to sweep, and the webapp would see them appear and disappear.
        assertEquals(first.getValue("4,64,4").id, reversed.getValue("4,64,4").id)
    }

    @Test
    fun `unrelated containers each stay their own group`() {
        val a = tag(1, x = 0, z = 0, groupKey = "0,64,0")
        val b = tag(2, x = 10, z = 10, groupKey = "10,64,10")
        val c = tag(3, x = 20, z = 20, groupKey = "20,64,20")

        val canonical = ContainerSweep.canonicalByGroup(listOf(a, b, c))

        assertEquals(3, canonical.size)
        assertEquals(setOf(1L, 2L, 3L), canonical.values.map { it.id }.toSet())
    }

    @Test
    fun `a blank group key falls back to the position, so it cannot swallow other containers`() {
        val a = tag(1, x = 0, z = 0, groupKey = "")
        val b = tag(2, x = 10, z = 10, groupKey = "")

        val canonical = ContainerSweep.canonicalByGroup(listOf(a, b))

        // Grouping them together would report one and silently erase the other from the total.
        assertEquals(2, canonical.size)
        assertTrue(canonical.keys.containsAll(setOf("0,64,0", "10,64,10")))
    }
}
