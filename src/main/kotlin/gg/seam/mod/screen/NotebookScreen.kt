package gg.seam.mod.screen

import gg.seam.mod.data.PlaceholderData
import gg.seam.mod.data.SampleContainer
import gg.seam.mod.data.SampleResource
import gg.seam.mod.data.SampleTask
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Phase 1 notebook shell (MCO-256): fixed header (title, project selector, status) and footer
 * (sync line, close), with a scissor-clipped, mouse-wheel-scrollable body holding resources,
 * tasks, and tagged containers. Placeholder data — real Seam data arrives in Phase 4.
 *
 * Built from ButtonWidget + DrawContext (the most version-stable 1.21.x APIs). See
 * docs/fabric-1.21.11-reference.md §4 — incl. the "don't call renderBackground()" blur gotcha.
 */
class NotebookScreen(private val projectIndex: Int = 0) : Screen(Text.literal("Seam Notebook")) {

    private val project get() = PlaceholderData.projects[projectIndex]

    // panel geometry
    private var left = 0
    private var top = 0
    private var panelW = 0
    private var panelH = 0
    private var contentX = 0
    private var contentR = 0

    // fixed regions
    private var statusY = 0
    private var bodyTop = 0
    private var bodyBottom = 0
    private var syncTextY = 0

    // scroll
    private var scroll = 0
    private var maxScroll = 0
    private var contentHeight = 0

    private lateinit var settingsButton: ButtonWidget
    private lateinit var projectButton: ButtonWidget
    private lateinit var closeButton: ButtonWidget
    private val taskButtons = mutableListOf<ButtonWidget>()

    override fun init() {
        val p = project
        panelW = minOf(360, width - 20)
        panelH = minOf(300, height - 20)
        left = (width - panelW) / 2
        top = (height - panelH) / 2
        contentX = left + PAD
        contentR = left + panelW - PAD

        // ---- fixed header ----
        settingsButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Settings")) { /* TODO MCO-257 */ }
                .dimensions(contentR - 64, top + 6, 64, BTN).build(),
        )
        val projectBtnY = top + 28
        projectButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Project: ${p.name}")) {
                client?.setScreen(NotebookScreen((projectIndex + 1) % PlaceholderData.projects.size))
            }.dimensions(contentX, projectBtnY, contentR - contentX, BTN).build(),
        )
        statusY = projectBtnY + BTN + 4

        // ---- fixed footer ----
        val footerTop = top + panelH - FOOTER_H
        syncTextY = footerTop + 8
        closeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Close")) { close() }
                .dimensions(contentR - 54, footerTop + 2, 54, BTN).build(),
        )

        // ---- scrollable body ----
        bodyTop = statusY + LINE + 4
        bodyBottom = footerTop - 2

        // task buttons live in the body; Y is set per-frame in render() as the body scrolls.
        taskButtons.clear()
        p.tasks.forEach { t ->
            taskButtons += addDrawableChild(
                ButtonWidget.builder(taskLabel(t)) { btn ->
                    t.done = !t.done
                    btn.message = taskLabel(t)
                }.dimensions(contentX, 0, contentR - contentX, BTN).build(),
            )
        }

        contentHeight = (LINE + 4) + p.resources.size * RES_ROW + GAP +
            (LINE + 4) + p.tasks.size * TASK_ROW + GAP +
            (LINE + 4) + p.containers.size * CON_ROW
        maxScroll = maxOf(0, contentHeight - (bodyBottom - bodyTop))
        scroll = scroll.coerceIn(0, maxScroll)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Do NOT call renderBackground() — framework applies the backdrop pre-render (blur once/frame).
        val p = project

        // panel + border
        context.fill(left, top, left + panelW, top + panelH, C_PANEL)
        context.fill(left, top, left + panelW, top + 1, C_BORDER)
        context.fill(left, top + panelH - 1, left + panelW, top + panelH, C_BORDER)
        context.fill(left, top, left + 1, top + panelH, C_BORDER)
        context.fill(left + panelW - 1, top, left + panelW, top + panelH, C_BORDER)

        // fixed header
        context.drawText(textRenderer, "SEAM NOTEBOOK", contentX, top + 12, C_INK, false)
        context.drawText(textRenderer, "Status: ${p.status}", contentX, statusY, C_MUTED, false)
        settingsButton.render(context, mouseX, mouseY, delta)
        projectButton.render(context, mouseX, mouseY, delta)

        // scrollable body
        context.enableScissor(left + 1, bodyTop, left + panelW - 1, bodyBottom)
        var cy = bodyTop - scroll
        cy = sectionHeader(context, "RESOURCES", cy)
        p.resources.forEach { r -> drawResource(context, r, cy); cy += RES_ROW }
        cy += GAP
        cy = sectionHeader(context, "TASKS", cy)
        p.tasks.forEachIndexed { i, _ ->
            val b = taskButtons[i]
            b.y = cy
            b.active = cy >= bodyTop && cy + BTN <= bodyBottom
            b.render(context, mouseX, mouseY, delta)
            cy += TASK_ROW
        }
        cy += GAP
        cy = sectionHeader(context, "CONTAINERS", cy)
        p.containers.forEach { c -> drawContainer(context, c, cy); cy += CON_ROW }
        context.disableScissor()

        // scrollbar
        if (maxScroll > 0) {
            val trackX = left + panelW - 5
            context.fill(trackX, bodyTop, trackX + 2, bodyBottom, C_TRACK)
            val viewH = bodyBottom - bodyTop
            val thumbH = maxOf(16, viewH * viewH / contentHeight)
            val thumbY = bodyTop + (viewH - thumbH) * scroll / maxScroll
            context.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, C_LAPIS)
        }

        // fixed footer
        context.drawText(textRenderer, "Last synced: — (offline placeholder)", contentX, syncTextY, C_MUTED, false)
        closeButton.render(context, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (maxScroll > 0) {
            scroll = (scroll - (verticalAmount * SCROLL_STEP).toInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    /** Draws a section header + underline at [y]; returns the y below it. */
    private fun sectionHeader(context: DrawContext, label: String, y: Int): Int {
        context.drawText(textRenderer, label, contentX, y, C_LAPIS, false)
        context.fill(contentX, y + 10, contentR, y + 11, C_BORDER)
        return y + LINE + 4
    }

    private fun drawResource(context: DrawContext, r: SampleResource, y: Int) {
        context.drawText(textRenderer, r.name, contentX, y, if (r.complete) C_DISABLED else C_INK, false)
        val barX = contentX + 96
        val barW = 84
        context.fill(barX, y + 1, barX + barW, y + 7, C_TRACK)
        val fw = (barW * r.progress).toInt()
        if (fw > 0) context.fill(barX, y + 1, barX + fw, y + 7, if (r.complete) C_GREEN else C_LAPIS)
        val count = "${r.have} / ${r.need}"
        context.drawText(
            textRenderer, count, contentR - textRenderer.getWidth(count), y,
            if (r.complete) C_GREEN else C_INK, false,
        )
        context.drawText(textRenderer, "Player ${r.player}   Storage ${r.storage}", contentX + 8, y + 11, C_MUTED, false)
    }

    private fun drawContainer(context: DrawContext, c: SampleContainer, y: Int) {
        context.drawText(textRenderer, "${c.type} @ ${c.x}, ${c.y}, ${c.z}", contentX, y, C_INK, false)
        val count = "${c.items} items"
        context.drawText(textRenderer, count, contentR - textRenderer.getWidth(count), y, C_MUTED, false)
    }

    private fun taskLabel(t: SampleTask): Text =
        Text.literal((if (t.done) "[x] " else "[ ] ") + t.name)

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(null)
    }

    private companion object {
        const val PAD = 14
        const val LINE = 10
        const val BTN = 20
        const val RES_ROW = 22
        const val TASK_ROW = 22
        const val CON_ROW = 12
        const val GAP = 6
        const val FOOTER_H = 26
        const val SCROLL_STEP = 14

        // Daylight Field Notebook tokens (ARGB — alpha required or text is invisible)
        const val C_PANEL = 0xFFFBF7EC.toInt()
        const val C_BORDER = 0xFF8A8069.toInt()
        const val C_INK = 0xFF2E2A24.toInt()
        const val C_MUTED = 0xFF7A7263.toInt()
        const val C_LAPIS = 0xFF2B5B8C.toInt()
        const val C_GREEN = 0xFF2F8F5B.toInt()
        const val C_TRACK = 0xFFD8CDBA.toInt()
        const val C_DISABLED = 0xFFB4AC9C.toInt()
    }
}
