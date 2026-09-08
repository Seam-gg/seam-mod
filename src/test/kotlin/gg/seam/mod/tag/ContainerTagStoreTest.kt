package gg.seam.mod.tag

import gg.seam.mod.api.ContainerTagDto
import gg.seam.mod.data.PendingContainerTag
import gg.seam.mod.data.WorldData
import gg.seam.mod.storage.ContainerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Which project the picker says a container belongs to (MCO-261).
 *
 * The interesting part is precedence. The player tags a chest, the write is queued, and the cached
 * tag list still says what the server last knew — which is the *old* answer. Showing that back
 * would look like the tag silently failed.
 */
class ContainerTagStoreTest {

    private fun target(vararg positions: TagPos, dimension: String = "minecraft:overworld") =
        ContainerTarget(ContainerKind.CHEST, dimension, positions.toList())

    private fun row(id: Long, x: Int, projectId: Int, dimension: String = "minecraft:overworld") =
        ContainerTagDto(id = id, projectId = projectId, dimension = dimension, x = x, y = 64, z = 20)

    private fun queued(x: Int, projectId: Int?, dimension: String = "minecraft:overworld") =
        PendingContainerTag(dimension, x, 64, 20, ContainerKind.CHEST, "$x,64,20", projectId)

    @Test
    fun `a container the server knows about reports its project and rows`() {
        val assignment = ContainerTagStore.assignmentFor(
            target(TagPos(10, 64, 20), TagPos(11, 64, 20)),
            data = WorldData(),
            knownTags = listOf(row(1, 10, projectId = 7), row(2, 11, projectId = 7)),
        )

        val synced = assertIs<ContainerTagStore.Assignment.Synced>(assignment)
        assertEquals(7, synced.projectId)
        // Both rows, because untagging a joined chest has to delete both.
        assertEquals(listOf(1L, 2L), synced.tagIds)
    }

    @Test
    fun `a queued tag wins over what the server last said`() {
        val assignment = ContainerTagStore.assignmentFor(
            target(TagPos(10, 64, 20)),
            data = WorldData().withPendingContainerTag(queued(10, projectId = 9)),
            knownTags = listOf(row(1, 10, projectId = 7)),
        )

        // The player just moved this chest to project 9. Rendering the server's 7 back at them
        // would read as the tag having failed.
        assertEquals(ContainerTagStore.Assignment.Queued(9), assignment)
    }

    @Test
    fun `a queued untag shows as queued, not as still tagged`() {
        val assignment = ContainerTagStore.assignmentFor(
            target(TagPos(10, 64, 20)),
            data = WorldData().withPendingContainerTag(queued(10, projectId = null)),
            knownTags = listOf(row(1, 10, projectId = 7)),
        )

        assertEquals(ContainerTagStore.Assignment.Queued(null), assignment)
    }

    @Test
    fun `an untagged container has no assignment`() {
        assertNull(
            ContainerTagStore.assignmentFor(
                target(TagPos(10, 64, 20)),
                data = WorldData(),
                knownTags = listOf(row(1, 999, projectId = 7)),
            ),
        )
    }

    @Test
    fun `the same coordinates in another dimension are a different container`() {
        // x/y/z alone is not an identity: every overworld position has a nether twin, and matching
        // on coordinates would show a nether chest as tagged because its overworld namesake is.
        assertNull(
            ContainerTagStore.assignmentFor(
                target(TagPos(10, 64, 20), dimension = "minecraft:the_nether"),
                data = WorldData(),
                knownTags = listOf(row(1, 10, projectId = 7, dimension = "minecraft:overworld")),
            ),
        )
    }

    @Test
    fun `a queued tag on either half of a pair speaks for the pair`() {
        // The gesture queues both halves, but a flush that got halfway through would leave one.
        // The picker must still say "queued" rather than flipping back to the old project.
        val assignment = ContainerTagStore.assignmentFor(
            target(TagPos(10, 64, 20), TagPos(11, 64, 20)),
            data = WorldData().withPendingContainerTag(queued(11, projectId = 9)),
            knownTags = listOf(row(1, 10, projectId = 7), row(2, 11, projectId = 7)),
        )

        assertEquals(ContainerTagStore.Assignment.Queued(9), assignment)
    }
}
