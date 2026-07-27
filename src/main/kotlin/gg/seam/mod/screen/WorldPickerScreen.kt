package gg.seam.mod.screen

import gg.seam.mod.api.ApiResult
import gg.seam.mod.api.SeamApi
import gg.seam.mod.api.WorldDto
import gg.seam.mod.api.describe
import gg.seam.mod.data.SeamDataStore
import gg.seam.mod.data.WorldDataStore
import gg.seam.mod.util.onClientThread
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Binds this local world (or server) to one of the player's Seam worlds (MCO-268).
 *
 * The mapping is manual by design: there is nothing in a Minecraft save that reliably identifies a
 * Seam world, so the player picks once and it is remembered per [gg.seam.mod.data.WorldKey].
 */
class WorldPickerScreen(private val parent: Screen?) : Screen(Text.literal("Seam world")) {

    private sealed interface State {
        data object Loading : State
        data class Loaded(val worlds: List<WorldDto>) : State
        data class Failed(val reason: String) : State
    }

    @Volatile
    private var state: State = State.Loading

    /** Set when [state] changes off-thread, so the next frame rebuilds the buttons. */
    @Volatile
    private var needsRebuild = false

    private var left = 0
    private var top = 0
    private var panelW = 0
    private var panelH = 0
    private var contentX = 0
    private var contentR = 0

    private val worldButtons = mutableListOf<ButtonWidget>()
    private lateinit var closeButton: ButtonWidget

    override fun init() {
        panelW = minOf(360, width - 20)
        panelH = minOf(220, height - 20)
        left = (width - panelW) / 2
        top = (height - panelH) / 2
        contentX = left + PAD
        contentR = left + panelW - PAD

        closeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Back")) { close() }
                .dimensions(contentR - 54, top + panelH - BTN - 8, 54, BTN).build(),
        )

        if (state is State.Loading) load()
        rebuildWorldButtons()
    }

    private fun load() {
        state = State.Loading
        SeamApi.client.getWorlds().thenAccept { result ->
            val next = when (result) {
                is ApiResult.Ok -> State.Loaded(result.value)
                is ApiResult.Error ->
                    if (result.status == 401) State.Failed("Your Seam link expired. Re-link in Settings.")
                    else State.Failed("Could not load worlds: ${result.describe()}")
                is ApiResult.Failure -> State.Failed("Could not reach Seam: ${result.describe()}")
            }
            onClientThread {
                state = next
                needsRebuild = true
            }
        }
    }

    private fun rebuildWorldButtons() {
        worldButtons.forEach { remove(it) }
        worldButtons.clear()
        val loaded = state as? State.Loaded ?: return

        val bound = WorldDataStore.current.seamWorldId
        var y = top + 48
        for (world in loaded.worlds.take(MAX_VISIBLE)) {
            val marker = if (world.id == bound) "* " else ""
            val label = textRenderer.trimToWidth("$marker${world.name}", contentR - contentX - 24)
            worldButtons += addDrawableChild(
                ButtonWidget.builder(Text.literal(label)) { bind(world) }
                    .dimensions(contentX, y, contentR - contentX, BTN).build(),
            )
            y += BTN + 2
        }
    }

    private fun bind(world: WorldDto) {
        // Project ids are scoped to a world, so any manual counts from a previous binding are
        // meaningless against the new one — withSeamWorld drops them when the id actually changes.
        WorldDataStore.update { it.withSeamWorld(world.id) }
        SeamDataStore.clear()
        SeamDataStore.refresh()
        close()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Do NOT call renderBackground() — see docs/fabric-1.21.11-reference.md §4.
        if (needsRebuild) {
            needsRebuild = false
            rebuildWorldButtons()
        }

        context.fill(left, top, left + panelW, top + panelH, SeamPalette.PANEL)
        context.fill(left, top, left + panelW, top + 1, SeamPalette.BORDER)
        context.fill(left, top + panelH - 1, left + panelW, top + panelH, SeamPalette.BORDER)
        context.fill(left, top, left + 1, top + panelH, SeamPalette.BORDER)
        context.fill(left + panelW - 1, top, left + panelW, top + panelH, SeamPalette.BORDER)

        context.drawText(textRenderer, "SEAM WORLD", contentX, top + 12, SeamPalette.INK, false)
        context.drawText(
            textRenderer,
            "Which Seam world is this save?",
            contentX,
            top + 28,
            SeamPalette.MUTED,
            false,
        )

        when (val current = state) {
            is State.Loading ->
                context.drawText(textRenderer, "Loading worlds...", contentX, top + 48, SeamPalette.MUTED, false)

            is State.Failed ->
                context.drawText(textRenderer, trim(current.reason), contentX, top + 48, SeamPalette.RED, false)

            is State.Loaded -> if (current.worlds.isEmpty()) {
                context.drawText(
                    textRenderer,
                    "No Seam worlds on your account yet.",
                    contentX, top + 48, SeamPalette.MUTED, false,
                )
            } else if (current.worlds.size > MAX_VISIBLE) {
                context.drawText(
                    textRenderer,
                    trim("Showing the first $MAX_VISIBLE of ${current.worlds.size} worlds."),
                    contentX, top + panelH - BTN - 22, SeamPalette.MUTED, false,
                )
            }
        }

        for (element in children()) (element as? Drawable)?.render(context, mouseX, mouseY, delta)
    }

    private fun trim(text: String): String = textRenderer.trimToWidth(text, contentR - contentX)

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    private companion object {
        const val PAD = 14
        const val BTN = 20

        /** Fits the panel without a scroll region; a scrollable list arrives if anyone outgrows it. */
        const val MAX_VISIBLE = 6
    }
}
