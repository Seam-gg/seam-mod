package gg.seam.mod.data

/**
 * In-memory sample data for the Phase 1 notebook shell (MCO-256) — replaced by real Seam data
 * pulled over the JSON API in Phase 4 (MCO-268).
 *
 * A tracked resource: [itemId] is the stable key into the manual-count store (MCO-272) — the live
 * count is read from [WorldDataStore], not held here. [need] is the target; there is no seed value.
 * (Phase 2 scanning will restore a player/storage breakdown.)
 */
data class SampleResource(
    val name: String,
    val itemId: String,
    val need: Int,
)

data class SampleTask(val name: String, var done: Boolean)

data class SampleContainer(val type: String, val x: Int, val y: Int, val z: Int, val items: Int)

/** [id] is the stable key into the manual-count store (MCO-272). */
data class SampleProject(
    val id: String,
    val name: String,
    val status: String,
    val resources: List<SampleResource>,
    val tasks: List<SampleTask>,
    val containers: List<SampleContainer>,
)

object PlaceholderData {
    val projects: List<SampleProject> = listOf(
        SampleProject(
            id = "iron_farm",
            name = "Iron Farm",
            status = "In Progress",
            resources = listOf(
                SampleResource("Iron Ingot", "minecraft:iron_ingot", 64),
                SampleResource("Redstone Dust", "minecraft:redstone", 8),
                SampleResource("Oak Planks", "minecraft:oak_planks", 16),
            ),
            tasks = listOf(
                SampleTask("Lay out foundation", false),
                SampleTask("Place hoppers", false),
                SampleTask("Dig out area", true),
            ),
            containers = listOf(
                SampleContainer("Chest", 120, 64, -430, 48),
                SampleContainer("Shulker Box", 122, 64, -430, 12),
            ),
        ),
        SampleProject(
            id = "auto_crafter",
            name = "Auto Crafter",
            status = "Planning",
            resources = listOf(
                SampleResource("Redstone Dust", "minecraft:redstone", 8),
                SampleResource("Dropper", "minecraft:dropper", 4),
            ),
            tasks = listOf(
                SampleTask("Sketch the design", true),
                SampleTask("Gather redstone", false),
            ),
            containers = listOf(
                SampleContainer("Barrel", 10, 70, 5, 20),
            ),
        ),
    )
}
