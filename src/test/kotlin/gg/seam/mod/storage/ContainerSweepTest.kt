package gg.seam.mod.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How tags are split into physical inventories (MCO-260).
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
    fun `a joined double chest is one group, so it is read once and not twice`() {
        val lower = tag(1, x = 4, z = 4, groupKey = "4,64,4")
        val upper = tag(2, x = 5, z = 4, groupKey = "4,64,4")

        val groups = ContainerSweep.groupsOf(listOf(lower, upper))

        // ChestBlock.getInventory(..., true) returns the COMBINED inventory from either half, so
        // reading both as separate inventories would count everything in the pair twice.
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups.single().map { it.id })
    }

    @Test
    fun `member order does not depend on input order, so a pass is repeatable`() {
        val lower = tag(1, x = 4, z = 4, groupKey = "4,64,4")
        val upper = tag(2, x = 5, z = 4, groupKey = "4,64,4")

        // Whichever member is readable first carries the pair's counts, so an unstable order would
        // hop the counts between the two tag rows from sweep to sweep.
        assertEquals(
            ContainerSweep.groupsOf(listOf(lower, upper)),
            ContainerSweep.groupsOf(listOf(upper, lower)),
        )
    }

    @Test
    fun `group order is stable, so the sweep cursor visits the same containers in the same order`() {
        val tags = listOf(
            tag(1, x = 0, z = 0, groupKey = "0,64,0"),
            tag(2, x = 10, z = 10, groupKey = "10,64,10"),
            tag(3, x = 20, z = 20, groupKey = "20,64,20"),
        )

        // A pass reads a few groups per tick and resumes next tick where it stopped. If the order
        // moved between ticks, a pass would re-read some containers and skip others entirely.
        assertEquals(ContainerSweep.groupsOf(tags), ContainerSweep.groupsOf(tags.reversed()))
    }

    @Test
    fun `unrelated containers each stay their own group`() {
        val a = tag(1, x = 0, z = 0, groupKey = "0,64,0")
        val b = tag(2, x = 10, z = 10, groupKey = "10,64,10")
        val c = tag(3, x = 20, z = 20, groupKey = "20,64,20")

        val groups = ContainerSweep.groupsOf(listOf(a, b, c))

        assertEquals(3, groups.size)
        assertTrue(groups.all { it.size == 1 })
        assertEquals(setOf(1L, 2L, 3L), groups.flatten().map { it.id }.toSet())
    }

    @Test
    fun `a blank group key falls back to the position, so it cannot swallow other containers`() {
        val a = tag(1, x = 0, z = 0, groupKey = "")
        val b = tag(2, x = 10, z = 10, groupKey = "")

        val groups = ContainerSweep.groupsOf(listOf(a, b))

        // Grouping them together would report one and silently zero the other, because every
        // non-holder member of a group is reported as contributing nothing.
        assertEquals(2, groups.size)
    }
}
