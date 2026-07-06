package gg.seam.mod.data

/**
 * In-memory sample data for the Phase 1 notebook shell (MCO-256). No backend.
 * Replaced by real Seam data pulled over the JSON API in Phase 4 (MCO-268).
 */
data class SampleResource(
    val name: String,
    val have: Int,
    val need: Int,
    val player: Int,
    val storage: Int,
) {
    val complete: Boolean get() = have >= need
    val progress: Float get() = if (need == 0) 1f else (have.toFloat() / need).coerceIn(0f, 1f)
}

data class SampleTask(val name: String, var done: Boolean)

data class SampleContainer(val type: String, val x: Int, val y: Int, val z: Int, val items: Int)

data class SampleProject(
    val name: String,
    val status: String,
    val resources: List<SampleResource>,
    val tasks: List<SampleTask>,
    val containers: List<SampleContainer>,
)

object PlaceholderData {
    val projects: List<SampleProject> = listOf(
        SampleProject(
            name = "Iron Farm",
            status = "In Progress",
            resources = listOf(
                SampleResource("Iron Ingot", 32, 64, 12, 20),
                SampleResource("Redstone Dust", 0, 8, 0, 0),
                SampleResource("Oak Planks", 16, 16, 16, 0),
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
            name = "Auto Crafter",
            status = "Planning",
            resources = listOf(
                SampleResource("Redstone Dust", 6, 8, 6, 0),
                SampleResource("Dropper", 2, 4, 2, 0),
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
