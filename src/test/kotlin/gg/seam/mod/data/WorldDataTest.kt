package gg.seam.mod.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldDataTest {

    // Mirrors SeamStorage.json without pulling in its I/O thread.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `counts round-trip through JSON with integer project ids`() {
        val data = WorldData(seamWorldId = 4).withCount(12, "minecraft:iron_ingot", 128)

        val decoded = json.decodeFromString(WorldData.serializer(), json.encodeToString(WorldData.serializer(), data))

        assertEquals(4, decoded.seamWorldId)
        assertEquals(128, decoded.count(12, "minecraft:iron_ingot"))
    }

    @Test
    fun `a count set back to zero is kept, so the sync can push the zero`() {
        val data = WorldData()
            .withCount(1, "minecraft:stone", 5)
            .withCount(1, "minecraft:stone", 0)

        assertEquals(0, data.count(1, "minecraft:stone"))
        // The key set is "items the player has touched" and the push is an absolute set. Pruning
        // the zero would make "I emptied this" indistinguishable from "I never touched this", and
        // the push would leave the old server count standing.
        assertTrue(
            data.resourceCounts.getValue(1).containsKey("minecraft:stone"),
            "a zeroed item must stay in the payload",
        )
    }

    @Test
    fun `editing a count queues the project for sync`() {
        val data = WorldData().withCount(3, "minecraft:stone", 5)

        assertEquals(setOf(3), data.pendingResourceProjects)
        assertEquals(1, data.queuedWrites)
    }

    @Test
    fun `a synced project drops both its counts and its queue entry`() {
        val data = WorldData().withCount(3, "minecraft:stone", 5).withProjectReset(3)

        assertTrue(data.resourceCounts.isEmpty())
        assertTrue(data.pendingResourceProjects.isEmpty())
        assertEquals(0, data.queuedWrites)
    }

    @Test
    fun `queued task toggles round-trip and clear individually`() {
        val data = WorldData()
            .withPendingTask(1, 10, completed = true)
            .withPendingTask(1, 11, completed = false)

        assertEquals(true, data.pendingTask(1, 10))
        assertEquals(false, data.pendingTask(1, 11))
        assertEquals(2, data.queuedWrites)

        val afterPush = data.withoutPendingTask(1, 10)
        assertNull(afterPush.pendingTask(1, 10))
        assertEquals(false, afterPush.pendingTask(1, 11))

        // The last toggle for a project takes the project entry with it.
        assertTrue(afterPush.withoutPendingTask(1, 11).pendingTasks.isEmpty())
    }

    @Test
    fun `rebinding a world discards queued writes, since ids are world-scoped`() {
        val data = WorldData(seamWorldId = 1)
            .withCount(7, "minecraft:stone", 64)
            .withPendingTask(7, 3, completed = true)

        val rebound = data.withSeamWorld(2)

        assertEquals(0, rebound.queuedWrites)
        assertNull(rebound.pendingTask(7, 3))
    }

    @Test
    fun `binding a different Seam world drops counts, since project ids are world-scoped`() {
        val data = WorldData(seamWorldId = 1).withCount(7, "minecraft:stone", 64)

        val rebound = data.withSeamWorld(2)

        assertEquals(2, rebound.seamWorldId)
        assertEquals(0, rebound.count(7, "minecraft:stone"))
    }

    @Test
    fun `re-binding the same Seam world keeps counts`() {
        val data = WorldData(seamWorldId = 1).withCount(7, "minecraft:stone", 64)

        val rebound = data.withSeamWorld(1)

        assertEquals(64, rebound.count(7, "minecraft:stone"))
    }

    @Test
    fun `a Phase 1 file with string project ids fails to decode, so SeamStorage quarantines it`() {
        // The placeholder build wrote ids like "iron_farm". Decoding must fail rather than silently
        // drop them — SeamStorage catches this, preserves the file as .corrupt and starts fresh.
        val legacy = """{"version":1,"resource_counts":{"iron_farm":{"minecraft:iron_ingot":64}}}"""

        assertFailsWith<Exception> { json.decodeFromString(WorldData.serializer(), legacy) }
    }

    @Test
    fun `an unbound world decodes with a null seam world id`() {
        val decoded = json.decodeFromString(WorldData.serializer(), """{"version":2}""")

        assertNull(decoded.seamWorldId)
        assertTrue(decoded.resourceCounts.isEmpty())
    }
}
