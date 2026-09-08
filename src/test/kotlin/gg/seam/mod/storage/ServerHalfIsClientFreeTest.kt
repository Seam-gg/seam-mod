package gg.seam.mod.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * MCO-534's load-bearing constraint, enforced rather than trusted: **nothing the server half loads
 * may reference `net.minecraft.client`.**
 *
 * `gg.seam.mod.storage` and `SeamServer` run from the `main` entrypoint, which loads on dedicated
 * servers — where the client classes do not exist at all. A stray reference does not fail to
 * compile and does not fail in a dev client; it fails at class-load on somebody else's server, long
 * after the jar shipped. That is exactly the kind of mistake a person cannot reliably catch by
 * reading, so it is caught here instead.
 *
 * The check reads the compiled classes and looks for the string in their constant pool, which is
 * where every type reference lands regardless of how it was written — an import, a fully-qualified
 * name, a Kotlin extension receiver, an inline function's body.
 */
class ServerHalfIsClientFreeTest {

    private val forbidden = "net/minecraft/client"

    @Test
    fun `the storage package carries no reference to MinecraftClient`() {
        val classes = compiledClassesUnder("gg/seam/mod/storage")
        assertTrue(classes.isNotEmpty(), "found no compiled classes to check — has the layout moved?")

        val offenders = classes.filter { Files.readAllBytes(it).containsAscii(forbidden) }
        if (offenders.isNotEmpty()) {
            fail(
                "these classes reference $forbidden and will not class-load on a dedicated server:\n" +
                    offenders.joinToString("\n") { "  - ${it.fileName}" }
            )
        }
    }

    @Test
    fun `the server entrypoint carries no reference to MinecraftClient`() {
        val entrypoint = compiledClassesUnder("gg/seam/mod").filter { it.fileName.toString().startsWith("SeamServer") }
        assertTrue(entrypoint.isNotEmpty(), "SeamServer.class not found — has the entrypoint moved?")

        val offenders = entrypoint.filter { Files.readAllBytes(it).containsAscii(forbidden) }
        assertTrue(offenders.isEmpty(), "SeamServer references $forbidden: $offenders")
    }

    /** Sanity check on the checker itself: the client half *does* reference it, and is detected. */
    @Test
    fun `the check can actually detect a client reference`() {
        val clientClasses = compiledClassesUnder("gg/seam/mod").filter {
            it.fileName.toString().startsWith("SeamClient")
        }
        assertTrue(clientClasses.isNotEmpty(), "SeamClient.class not found")
        assertTrue(
            clientClasses.any { Files.readAllBytes(it).containsAscii(forbidden) },
            "the client half should reference $forbidden — if it does not, this test proves nothing",
        )
    }

    private fun compiledClassesUnder(packagePath: String): List<Path> {
        val root = classesRoot().resolve(packagePath)
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).asSequence()
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
            .toList()
    }

    /** `build/classes/kotlin/main`, found relative to the module rather than hard-coded from CWD. */
    private fun classesRoot(): Path {
        val fromProperty = System.getProperty("seam.classesRoot")
        if (fromProperty != null) return Paths.get(fromProperty)
        val cwd = Paths.get("").toAbsolutePath()
        return cwd.resolve("build/classes/kotlin/main")
    }

    private fun ByteArray.containsAscii(needle: String): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        if (target.isEmpty() || size < target.size) return false
        outer@ for (start in 0..size - target.size) {
            for (i in target.indices) {
                if (this[start + i] != target[i]) continue@outer
            }
            return true
        }
        return false
    }
}
