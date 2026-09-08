package gg.seam.mod.storage

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reporter's config (MCO-534). The cases that matter are the two "no" answers: an absent file
 * is a supported state and a malformed one must not take the server down with it.
 */
class ReporterConfigTest {

    private val log = LoggerFactory.getLogger(ReporterConfigTest::class.java)
    private val dir: Path = createTempDirectory("seam-reporter-config")

    private fun write(json: String): Path {
        val path = dir.resolve("seam-notebook-server-${System.nanoTime()}.json")
        Files.writeString(path, json)
        return path
    }

    @Test
    fun `an absent file is not an error, it is a server that has not turned the reporter on`() {
        assertNull(ReporterConfig.load(dir.resolve("does-not-exist.json"), log))
    }

    @Test
    fun `a malformed file is a logged failure, not a crash`() {
        // Someone tried and got it wrong. The server must still boot.
        assertNull(ReporterConfig.load(write("{ this is not json"), log))
    }

    @Test
    fun `a complete config parses`() {
        val config = ReporterConfig.load(
            write(
                """
                {"api_base_url":"http://localhost:8080","seam_world_id":3,
                 "token":"abc","sweep_seconds":45,"reads_per_tick":16}
                """.trimIndent()
            ),
            log,
        )

        assertNotNull(config)
        assertEquals("http://localhost:8080", config.apiBaseUrl)
        assertEquals(3, config.seamWorldId)
        assertEquals(45, config.sweepSeconds)
        assertEquals(16, config.readsPerTick)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `unknown keys are tolerated, so a newer config does not break an older jar`() {
        val config = ReporterConfig.load(
            write("""{"seam_world_id":3,"token":"abc","some_future_option":true}"""),
            log,
        )

        assertNotNull(config)
        assertEquals(3, config.seamWorldId)
        // Unset fields fall back to their defaults rather than failing the parse.
        assertEquals(ReporterConfig.DEFAULT_SWEEP_SECONDS, config.sweepSeconds)
        assertEquals(ReporterConfig.DEFAULT_BASE_URL, config.apiBaseUrl)
    }

    @Test
    fun `a config without a token or a world is parsed but not configured`() {
        val noToken = ReporterConfig.load(write("""{"seam_world_id":3,"token":""}"""), log)
        assertNotNull(noToken)
        assertFalse(noToken.isConfigured, "no credential means nothing to report with")

        val noWorld = ReporterConfig.load(write("""{"token":"abc"}"""), log)
        assertNotNull(noWorld)
        assertFalse(noWorld.isConfigured, "nothing says which Seam world this is")
    }

    @Test
    fun `absurd values are clamped rather than trusted`() {
        val config = ReporterConfig.load(
            write("""{"seam_world_id":3,"token":"abc","sweep_seconds":0,"reads_per_tick":100000}"""),
            log,
        )

        assertNotNull(config)
        // A hand-edited file should be able to slow the sweep down, not stall the tick loop.
        assertTrue(config.sweepSeconds >= 5, "sweep_seconds=${config.sweepSeconds}")
        assertTrue(config.readsPerTick <= 256, "reads_per_tick=${config.readsPerTick}")
    }

    @Test
    fun `save then load round-trips, and writes every tunable`() {
        val path = dir.resolve("written-${System.nanoTime()}.json")
        val original = ReporterConfig(
            apiBaseUrl = "http://localhost:8080",
            seamWorldId = 7,
            token = "abc",
            sweepSeconds = 45,
            readsPerTick = 12,
        )

        assertTrue(ReporterConfig.save(original, path).isSuccess)
        assertEquals(original, ReporterConfig.load(path, log))

        // The file is meant to be opened and hand-tuned, so a tunable sitting at its default must
        // still appear — otherwise nobody knows it is there to change.
        val written = Files.readString(path)
        assertTrue(written.contains("sweep_seconds"), written)
        assertTrue(written.contains("reads_per_tick"), written)
        assertTrue(written.contains("api_base_url"), written)
    }

    @Test
    fun `a saved config with defaults still names every field`() {
        val path = dir.resolve("defaults-${System.nanoTime()}.json")
        ReporterConfig.save(ReporterConfig(seamWorldId = 1, token = "t"), path)

        val written = Files.readString(path)
        assertTrue(written.contains("sweep_seconds"), "defaults must be written, not omitted: $written")
        assertTrue(written.contains("reads_per_tick"), written)
    }

    @Test
    fun `toString does not carry the token`() {
        val rendered = ReporterConfig(seamWorldId = 3, token = "SEEDED-REPORTER-TOKEN").toString()

        // This object is exactly the kind of thing that ends up in a debug log.
        assertFalse(rendered.contains("SEEDED-REPORTER-TOKEN"), "the token survived toString(): $rendered")
        assertTrue(rendered.contains("redacted"))
        // The debuggable half survives.
        assertTrue(rendered.contains("seamWorldId=3"))
    }
}
