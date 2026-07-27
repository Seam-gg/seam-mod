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
    fun `setting a count to zero removes the entry rather than storing a zero`() {
        val data = WorldData()
            .withCount(1, "minecraft:stone", 5)
            .withCount(1, "minecraft:stone", 0)

        assertEquals(0, data.count(1, "minecraft:stone"))
        assertTrue(data.resourceCounts.isEmpty(), "empty project maps should be pruned too")
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
