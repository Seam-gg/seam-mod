package gg.seam.mod.screen

import gg.seam.mod.data.PlaceholderData
import gg.seam.mod.data.SampleContainer
import gg.seam.mod.data.SampleResource
import gg.seam.mod.data.SampleTask
import gg.seam.mod.data.WorldDataStore
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

    // Five batch buttons per resource ([-1][-64] left, [+1][+64][+1728] right), parallel to
    // project.resources. Y is set per-frame in render() as the body scrolls (like taskButtons);
    // X/width are fixed here in init().
    private val batchButtons = mutableListOf<List<ButtonWidget>>()

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

        // batch counter buttons live on the resource row's second line: minus cluster left-aligned
        // (small indent), plus cluster right-aligned to contentR. X/width fixed here; Y set per-frame
        // in render(). Counts persist through WorldDataStore, keyed project id → item id (MCO-272).
        // 64 = a stack, 1728 = a double chest of stacks — deltas match the webapp.
        val pid = p.id
        batchButtons.clear()
        p.resources.forEach { r ->
            val widths = IntArray(BATCH_DELTAS.size) { textRenderer.getWidth(batchLabel(BATCH_DELTAS[it])) + BTN_PAD_X * 2 }
            val xs = IntArray(BATCH_DELTAS.size)
            // minus cluster: [-1][-64] left-aligned at contentX + 8
            xs[0] = contentX + 8
            xs[1] = xs[0] + widths[0] + BTN_GAP
            // plus cluster: [+1][+64][+1728] right-aligned to contentR
            xs[2] = contentR - (widths[2] + widths[3] + widths[4] + 2 * BTN_GAP)
            xs[3] = xs[2] + widths[2] + BTN_GAP
            xs[4] = xs[3] + widths[3] + BTN_GAP
            batchButtons += BATCH_DELTAS.mapIndexed { idx, delta ->
                addDrawableChild(
                    ButtonWidget.builder(Text.literal(batchLabel(delta))) {
                        // read + write inside the transform so the update is atomic; clamp ≥ 0.
                        WorldDataStore.update { d ->
                            d.withCount(pid, r.itemId, (d.count(pid, r.itemId) + delta).coerceAtLeast(0))
                        }
                    }.dimensions(xs[idx], 0, widths[idx], BATCH_BTN).build(),
                )
            }
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
        p.resources.forEachIndexed { i, r ->
            drawResource(context, p.id, r, cy)
            // batch buttons sit on the row's second line; gate .active to the whole row being visible.
            val visible = cy >= bodyTop && cy + RES_ROW <= bodyBottom
            batchButtons[i].forEach { b ->
                b.y = cy + TOP_H
                b.active = visible
                b.render(context, mouseX, mouseY, delta)
            }
            cy += RES_ROW
        }
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

    private fun drawResource(context: DrawContext, projectId: String, r: SampleResource, y: Int) {
        // Live count comes from the manual-count store (MCO-272), not a static seed. Phase 2 scanning
        // will restore the player/storage breakdown that used to live here.
        val have = WorldDataStore.current.count(projectId, r.itemId)
        val complete = have >= r.need
        val progress = if (r.need == 0) 1f else (have.toFloat() / r.need).coerceIn(0f, 1f)
        // top line: name (left), bar, count (right) all share a vertical center within TOP_H so the
        // 6px bar and the text baseline line up (the batch button row follows below).
        val centerY = y + TOP_H / 2
        val textY = centerY - textRenderer.fontHeight / 2
        val barTop = centerY - 3
        context.drawText(textRenderer, r.name, contentX, textY, if (complete) C_DISABLED else C_INK, false)
        val barX = contentX + 96
        val barW = 84
        context.fill(barX, barTop, barX + barW, barTop + 6, C_TRACK)
        val fw = (barW * progress).toInt()
        if (fw > 0) context.fill(barX, barTop, barX + fw, barTop + 6, if (complete) C_GREEN else C_LAPIS)
        val count = "$have / ${r.need}"
        context.drawText(
            textRenderer, count, contentR - textRenderer.getWidth(count), textY,
            if (complete) C_GREEN else C_INK, false,
        )
    }

    private fun drawContainer(context: DrawContext, c: SampleContainer, y: Int) {
        context.drawText(textRenderer, "${c.type} @ ${c.x}, ${c.y}, ${c.z}", contentX, y, C_INK, false)
        val count = "${c.items} items"
        context.drawText(textRenderer, count, contentR - textRenderer.getWidth(count), y, C_MUTED, false)
    }

    private fun taskLabel(t: SampleTask): Text =
        Text.literal((if (t.done) "[x] " else "[ ] ") + t.name)

    /** Signed label for a batch button: "+1", "+64", "+1728", "-1", "-64" (negatives keep their sign). */
    private fun batchLabel(delta: Int): String = if (delta >= 0) "+$delta" else "$delta"

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(null)
    }

    private companion object {
        const val PAD = 14
        const val LINE = 10
        const val BTN = 20
        const val TOP_H = 13 // top line height (name / bar / count) above the batch button row
        const val BATCH_BTN = 16 // shorter height for the batch [-1]…[+1728] buttons
        const val BTN_PAD_X = 5 // horizontal padding added to each batch button's label width
        const val BTN_GAP = 3 // gap between adjacent batch buttons
        val BATCH_DELTAS = intArrayOf(-1, -64, 1, 64, 1728)
        const val RES_ROW = 34 // TOP_H (13) + BATCH_BTN (16) + trailing pad
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
