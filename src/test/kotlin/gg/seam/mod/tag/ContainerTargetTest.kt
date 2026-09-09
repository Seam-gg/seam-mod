package gg.seam.mod.tag

import gg.seam.mod.storage.ContainerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of tagging that decides what gets posted (MCO-261).
 *
 * Resolving a block into a target needs a world, so that part belongs to a running game. What is
 * testable here is the part that would silently double every count in the system: whether both
 * halves of a joined chest agree on one `group_key`, whichever half the player clicked.
 */
class ContainerTargetTest {

    private fun pair(vararg positions: TagPos) =
        ContainerTarget(ContainerKind.CHEST, "minecraft:overworld", positions.toList())

    @Test
    fun `both halves of a joined chest produce the same group key`() {
        val clickedLeft = pair(TagPos(10, 64, 20), TagPos(11, 64, 20))
        val clickedRight = pair(TagPos(11, 64, 20), TagPos(10, 64, 20))

        // The player clicks one half; which one must not change the identity of the inventory. If
        // it did, the two rows would land in different groups and the sweep would read the pair
        // twice — the bug the group key exists to prevent.
        assertEquals(clickedLeft.groupKey, clickedRight.groupKey)
        assertEquals("10,64,20", clickedLeft.groupKey)
    }

    @Test
    fun `a pair posts both rows, and both carry the shared key`() {
        val requests = pair(TagPos(11, 64, 20), TagPos(10, 64, 20)).requests(projectId = 7)

        assertEquals(2, requests.size)
        assertEquals(listOf("10,64,20", "10,64,20"), requests.map { it.groupKey })
        assertEquals(setOf(10 to 20, 11 to 20), requests.map { it.x to it.z }.toSet())
        assertTrue(requests.all { it.projectId == 7 && it.kind == ContainerKind.CHEST })
    }

    @Test
    fun `the group key is a position of the container itself, which is all the webapp accepts`() {
        val target = pair(TagPos(11, 64, 20), TagPos(10, 64, 20))

        // mc-org rejects a group_key that is not the container's own position or an adjacent one,
        // so an invented key would be a 400 at tagging time rather than a wrong count later.
        assertTrue(target.positions.map { it.toString() }.contains(target.groupKey))
    }

    @Test
    fun `a lone container is its own group`() {
        val single = ContainerTarget(ContainerKind.BARREL, "minecraft:overworld", listOf(TagPos(3, 70, -4)))

        assertFalse(single.isPair)
        assertEquals("3,70,-4", single.groupKey)
        assertEquals(1, single.requests(projectId = 1).size)
    }

    @Test
    fun `the key carries y, so a barrel stacked on a barrel is not the same inventory`() {
        // The lowest (x, z) picks the member, but the key it produces is the full position. Two
        // barrels in a column share (x, z), and a key that dropped y would collapse them into one
        // group — reporting one and silently zeroing the other.
        val lower = ContainerTarget(ContainerKind.BARREL, "minecraft:overworld", listOf(TagPos(5, 64, 5)))
        val upper = ContainerTarget(ContainerKind.BARREL, "minecraft:overworld", listOf(TagPos(5, 65, 5)))

        assertTrue(lower.groupKey != upper.groupKey, "two barrels in a column shared a group key")
    }
}
