package gg.seam.mod.util

import gg.seam.mod.SeamClient
import net.minecraft.util.Util
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opens URLs in the player's browser.
 *
 * **Always off the client thread.** Launching a browser shells out to a platform handler, and on
 * WSL that hands off to a Windows process — a call that can block for seconds. Doing it on the
 * render thread would freeze the game, so the work is queued onto a dedicated daemon thread.
 *
 * **Why not just `Util.getOperatingSystem().open(…)`:** on Linux that runs `xdg-open`, which is not
 * installed on a bare WSL distro — Minecraft logs "Couldn't open location" and returns normally, so
 * the caller can't even tell it failed. WSL needs `wslview` or `explorer.exe` instead. We try a
 * chain of openers and only give up once every one of them has actually failed.
 */
object Browser {

    /** WSL: vanilla's `xdg-open` is usually absent; these hand the URL to the Windows default browser. */
    private val WSL_OPENERS = listOf("wslview", "explorer.exe")

    /** Generic desktop-Linux openers, tried when vanilla's own attempt didn't take. */
    private val UNIX_OPENERS = listOf("xdg-open", "sensible-browser", "x-www-browser")

    /** Read from /proc — an env var (WSL_DISTRO_NAME) isn't reliable inside the game's process. */
    private val isWsl: Boolean by lazy {
        runCatching {
            java.io.File("/proc/sys/kernel/osrelease").readText().contains("microsoft", ignoreCase = true)
        }.getOrDefault(false)
    }

    private val opener = Executors.newSingleThreadExecutor { r ->
        Thread(r, "seam-browser").apply { isDaemon = true }
    }

    /** Best-effort open of [url]. Returns immediately; the player always has a fallback chat link. */
    fun open(url: String) {
        opener.execute {
            if (openNow(url)) return@execute
            SeamClient.logger.warn("Could not open $url in a browser — use the link in chat instead")
        }
    }

    private fun openNow(url: String): Boolean {
        // On WSL the platform openers come first: vanilla would just fail on a missing xdg-open.
        // Everywhere else vanilla is the best first guess — it is correct on Windows and macOS.
        if (isWsl && WSL_OPENERS.any { run(it, url) }) return true
        if (!isWsl && vanillaOpen(url)) return true
        return UNIX_OPENERS.any { run(it, url) }
    }

    private fun vanillaOpen(url: String): Boolean =
        runCatching { Util.getOperatingSystem().open(URI.create(url)); true }.getOrDefault(false)

    /**
     * Run `<command> <url>`, returning whether it plausibly opened something. A missing binary
     * throws [java.io.IOException] from `start()` and is reported as a clean failure, which is what
     * drives the fallback chain.
     */
    private fun run(command: String, url: String): Boolean = runCatching {
        val process = ProcessBuilder(command, url)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            // Some launchers stay attached to the browser they spawned. Not a failure — the page is open.
            process.destroy()
            return@runCatching true
        }
        // explorer.exe habitually exits non-zero even when the browser opened fine, so its exit
        // code says nothing; reaching this point at all means the binary existed and ran.
        process.exitValue() == 0 || command == "explorer.exe"
    }.getOrDefault(false)

    private const val LAUNCH_TIMEOUT_SECONDS = 10L
}
