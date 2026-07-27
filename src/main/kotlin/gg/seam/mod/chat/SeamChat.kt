package gg.seam.mod.chat

import gg.seam.mod.util.onClientThread
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import java.net.URI

/**
 * Client-side `[Seam]` chat output. These messages are drawn straight into the local chat HUD — no
 * packet, no server round trip — so they work identically in singleplayer and on a server that
 * knows nothing about this mod. See docs/fabric-1.21.11-reference.md §7.
 *
 * Every entry point marshals to the client thread itself, so callers can shout from an HTTP
 * callback without thinking about it.
 */
object SeamChat {

    /** Neutral `[Seam] …` line. */
    fun info(message: String) = send(Text.literal(message).styled { it.withColor(TextColor.fromRgb(C_INK)) })

    /** Failure line, rendered in the design system's red. */
    fun error(message: String) = send(Text.literal(message).styled { it.withColor(TextColor.fromRgb(C_RED)) })

    /** Success line, rendered in the design system's green. */
    fun success(message: String) = send(Text.literal(message).styled { it.withColor(TextColor.fromRgb(C_GREEN)) })

    /** Append the `[Seam] ` prefix to [body] and post it to the local chat HUD. */
    fun send(body: Text) {
        onClientThread {
            MinecraftClient.getInstance().inGameHud?.chatHud?.addMessage(prefixed(body))
        }
    }

    /**
     * A clickable link component. 1.21.5 turned `ClickEvent` into a sealed interface of records, so
     * this is `ClickEvent.OpenUrl(URI)` — not the old `ClickEvent(Action, String)` (hazard H2).
     *
     * There is no clean client-only "run my lambda on click", so links always open a browser.
     */
    fun link(label: String, url: String): MutableText =
        Text.literal(label).styled {
            it.withColor(TextColor.fromRgb(C_LAPIS))
                .withUnderline(true)
                .withClickEvent(ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(HoverEvent.ShowText(Text.literal("Open $url")))
        }

    /** Monospace-ish emphasis for a device code the player has to type. */
    fun code(value: String): MutableText =
        Text.literal(value).styled { it.withColor(TextColor.fromRgb(C_LAPIS)).withBold(true) }

    // Rooted on an empty component: Text children inherit their parent's style, so hanging [body]
    // off the bold-lapis prefix would make the whole line bold lapis.
    private fun prefixed(body: Text): Text =
        Text.empty()
            .append(Text.literal("[Seam] ").styled { it.withColor(TextColor.fromRgb(C_LAPIS)).withBold(true) })
            .append(body)

    // Daylight Field Notebook tokens (RGB — chat colors carry no alpha channel).
    private const val C_LAPIS = 0x2B5B8C
    private const val C_INK = 0xE8E2D4      // light ink: chat sits on the dark HUD, not on paper
    private const val C_GREEN = 0x5FD39B
    private const val C_RED = 0xE0736B
}
