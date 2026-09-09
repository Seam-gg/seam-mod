package gg.seam.mod

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `KeyBinding.Category.create` may be called **once** per category id.
 *
 * It registers the category rather than constructing a value, and a second call with the same id
 * throws `IllegalArgumentException: Category '<id>' is already registered` — during entrypoint
 * init, so the client dies before the main menu. That is a launch failure, not a bug anyone can
 * play around, and it shipped the moment a second keybind was added (MCO-261's tag key).
 *
 * Nothing else can catch it: the failure needs a real client, and every unit test here runs without
 * one. So this reads the source. That is unusual and deliberate — a brittle test for a crash on
 * startup is a better trade than no test at all, and the next keybind (MCO-264's label toggle is
 * the obvious one) will otherwise reintroduce it.
 */
class KeyBindingCategoryTest {

    private val source = File("src/main/kotlin/gg/seam/mod/SeamClient.kt")

    @Test
    fun `the client registers its keybind category exactly once`() {
        assertTrue(source.isFile, "SeamClient.kt moved; this test needs its new path")

        // Comments stripped first: the explanation next to the call names the method in prose, and
        // counting that as a call site would make this test fail on its own documentation.
        val code = source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        val callSites = Regex("""Category\.create\s*\(""").findAll(code).count()

        assertEquals(
            1, callSites,
            "KeyBinding.Category.create is called $callSites times. It registers, so the second " +
                "call throws and the client cannot start. Hoist it to one `val category = ...` and " +
                "pass that to every KeyBinding.",
        )
    }
}
