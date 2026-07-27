package gg.seam.mod.screen

import gg.seam.mod.api.ProjectDto
import gg.seam.mod.api.ResourceDto
import gg.seam.mod.api.TaskDto
import gg.seam.mod.data.SeamData
import gg.seam.mod.data.SeamDataStore
import gg.seam.mod.data.WorldDataStore
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The notebook (MCO-256 shell, real Seam data since MCO-268): fixed header (title, project
 * selector, status) and footer (sync line, close), with a scissor-clipped, scrollable body holding
 * resources and tasks.
 *
 * Projects come from [SeamDataStore], refreshed when the screen opens. Every non-loaded state gets
 * its own message, because "no projects" has several very different causes and fixes.
 *
 * Built from ButtonWidget + DrawContext (the most version-stable 1.21.x APIs). See
 * docs/fabric-1.21.11-reference.md §4 — incl. the "don't call renderBackground()" blur gotcha.
 */
class NotebookScreen(private var projectIndex: Int = 0) : Screen(Text.literal("Seam Notebook")) {

    private val projects get() = SeamDataStore.projects
    private val project get() = projects.getOrNull(projectIndex)

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
    private lateinit var refreshButton: ButtonWidget
    private lateinit var closeButton: ButtonWidget

    /**
     * Five batch buttons per resource ([-1][-64] left, [+1][+64][+1728] right), parallel to the
     * current project's resources. Y is set per-frame in render() as the body scrolls; X/width are
     * fixed in init().
     */
    private val batchButtons = mutableListOf<List<ButtonWidget>>()

    /**
     * Which project [batchButtons] were built for. Each button closes over its project id and item
     * id, so when a background refresh swaps the project set underneath us the buttons have to be
     * rebuilt — comparing sizes alone would miss two different projects with equal resource counts
     * and leave buttons writing counts against the previous project.
     */
    private var builtForProjectId: Int? = null

    override fun init() {
        panelW = minOf(360, width - 20)
        panelH = minOf(300, height - 20)
        left = (width - panelW) / 2
        top = (height - panelH) / 2
        contentX = left + PAD
        contentR = left + panelW - PAD

        // Pull on open — the default sync frequency. Stale-guarded because init() also re-runs on
        // every window resize. Cheap when the token or binding is absent: refresh() short-circuits
        // to Unlinked/Unmapped without touching the network.
        SeamDataStore.refreshIfStale()

        // ---- fixed header ----
        settingsButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Settings")) { client?.setScreen(SettingsScreen(this)) }
                .dimensions(contentR - 64, top + 6, 64, BTN).build(),
        )
        val projectBtnY = top + 28
        projectButton = addDrawableChild(
            ButtonWidget.builder(Text.literal(projectLabel())) { cycleProject() }
                .dimensions(contentX, projectBtnY, contentR - contentX - 60, BTN).build(),
        )
        refreshButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Refresh")) { SeamDataStore.refresh() }
                .dimensions(contentR - 56, projectBtnY, 56, BTN).build(),
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

        buildResourceButtons()
    }

    /**
     * Batch counter buttons for the selected project's resources: minus cluster left-aligned, plus
     * cluster right-aligned to contentR. Counts persist through [WorldDataStore], keyed project id →
     * item id (MCO-272). 64 = a stack, 1728 = a double chest of stacks — deltas match the webapp.
     */
    private fun buildResourceButtons() {
        batchButtons.forEach { row -> row.forEach { remove(it) } }
        batchButtons.clear()

        val current = project
        builtForProjectId = current?.id
        if (current == null) {
            recomputeContentHeight()
            return
        }
        current.resources.forEach { resource ->
            val widths = IntArray(BATCH_DELTAS.size) { textRenderer.getWidth(batchLabel(BATCH_DELTAS[it])) + BTN_PAD_X * 2 }
            val xs = IntArray(BATCH_DELTAS.size)
            xs[0] = contentX + 8
            xs[1] = xs[0] + widths[0] + BTN_GAP
            xs[2] = contentR - (widths[2] + widths[3] + widths[4] + 2 * BTN_GAP)
            xs[3] = xs[2] + widths[2] + BTN_GAP
            xs[4] = xs[3] + widths[3] + BTN_GAP
            batchButtons += BATCH_DELTAS.mapIndexed { idx, delta ->
                addDrawableChild(
                    ButtonWidget.builder(Text.literal(batchLabel(delta))) {
                        // read + write inside the transform so the update is atomic; clamp >= 0.
                        WorldDataStore.update { data ->
                            data.withCount(
                                current.id,
                                resource.itemId,
                                (data.count(current.id, resource.itemId) + delta).coerceAtLeast(0),
                            )
                        }
                    }.dimensions(xs[idx], 0, widths[idx], BATCH_BTN).build(),
                )
            }
        }
        recomputeContentHeight()
    }

    private fun recomputeContentHeight() {
        val current = project
        contentHeight = if (current == null) {
            LINE * 4
        } else {
            (LINE + 4) + current.resources.size * RES_ROW + GAP + (LINE + 4) + current.tasks.size * TASK_ROW
        }
        maxScroll = maxOf(0, contentHeight - (bodyBottom - bodyTop))
        scroll = scroll.coerceIn(0, maxScroll)
    }

    private fun cycleProject() {
        if (projects.isEmpty()) return
        projectIndex = (projectIndex + 1) % projects.size
        scroll = 0
        projectButton.message = Text.literal(projectLabel())
        buildResourceButtons()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Do NOT call renderBackground() — framework applies the backdrop pre-render (blur once/frame).
        val data = SeamDataStore.state

        // The pull completes on a background thread; rebuild the rows on the first frame after the
        // project set changes underneath us.
        if (projectIndex >= projects.size) projectIndex = 0
        if (project?.id != builtForProjectId) buildResourceButtons()

        // panel + border
        context.fill(left, top, left + panelW, top + panelH, SeamPalette.PANEL)
        context.fill(left, top, left + panelW, top + 1, SeamPalette.BORDER)
        context.fill(left, top + panelH - 1, left + panelW, top + panelH, SeamPalette.BORDER)
        context.fill(left, top, left + 1, top + panelH, SeamPalette.BORDER)
        context.fill(left + panelW - 1, top, left + panelW, top + panelH, SeamPalette.BORDER)

        // fixed header
        context.drawText(textRenderer, "SEAM NOTEBOOK", contentX, top + 12, SeamPalette.INK, false)
        projectButton.message = Text.literal(projectLabel())
        projectButton.active = projects.size > 1
        context.drawText(textRenderer, statusLine(), contentX, statusY, SeamPalette.MUTED, false)
        settingsButton.render(context, mouseX, mouseY, delta)
        projectButton.render(context, mouseX, mouseY, delta)
        refreshButton.render(context, mouseX, mouseY, delta)

        // scrollable body
        context.enableScissor(left + 1, bodyTop, left + panelW - 1, bodyBottom)
        val current = project
        if (current == null) {
            drawEmptyBody(context, data)
        } else {
            drawProjectBody(context, current, mouseX, mouseY, delta)
        }
        context.disableScissor()

        // scrollbar
        if (maxScroll > 0) {
            val trackX = left + panelW - 5
            context.fill(trackX, bodyTop, trackX + 2, bodyBottom, SeamPalette.TRACK)
            val viewH = bodyBottom - bodyTop
            val thumbH = maxOf(16, viewH * viewH / contentHeight)
            val thumbY = bodyTop + (viewH - thumbH) * scroll / maxScroll
            context.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, SeamPalette.LAPIS)
        }

        // fixed footer
        context.drawText(textRenderer, syncLine(data), contentX, syncTextY, SeamPalette.MUTED, false)
        closeButton.render(context, mouseX, mouseY, delta)
    }

    private fun drawProjectBody(
        context: DrawContext,
        current: ProjectDto,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        var cy = bodyTop - scroll
        cy = sectionHeader(context, "RESOURCES", cy)
        if (current.resources.isEmpty()) {
            context.drawText(textRenderer, "No resources tracked yet.", contentX, cy, SeamPalette.MUTED, false)
            cy += LINE + 4
        }
        current.resources.forEachIndexed { i, resource ->
            drawResource(context, current.id, resource, cy)
            val visible = cy >= bodyTop && cy + RES_ROW <= bodyBottom
            batchButtons.getOrNull(i)?.forEach { button ->
                button.y = cy + TOP_H
                button.active = visible
                button.render(context, mouseX, mouseY, delta)
            }
            cy += RES_ROW
        }
        cy += GAP
        cy = sectionHeader(context, "TASKS", cy)
        if (current.tasks.isEmpty()) {
            context.drawText(textRenderer, "No tasks yet.", contentX, cy, SeamPalette.MUTED, false)
        }
        // Read-only for now: toggling a task is a write, and writes land with MCO-269.
        current.tasks.forEach { task ->
            drawTask(context, task, cy)
            cy += TASK_ROW
        }
    }

    /** Body for every state that has no project to show — each with the action that fixes it. */
    private fun drawEmptyBody(context: DrawContext, data: SeamData) {
        val lines = when (data) {
            is SeamData.Unlinked -> listOf(
                "No Seam account linked." to SeamPalette.INK,
                "Open Settings and choose Link account." to SeamPalette.MUTED,
            )
            is SeamData.Unmapped -> listOf(
                "This world isn't linked to a Seam world." to SeamPalette.INK,
                "Open Settings and pick one under Seam world." to SeamPalette.MUTED,
            )
            is SeamData.Loading -> listOf("Loading projects..." to SeamPalette.MUTED)
            is SeamData.Failed -> listOf(
                data.reason to SeamPalette.RED,
                "Press Refresh to try again." to SeamPalette.MUTED,
            )
            is SeamData.Loaded -> listOf(
                "This Seam world has no projects yet." to SeamPalette.INK,
                "Create one in the Seam web app." to SeamPalette.MUTED,
            )
        }
        var cy = bodyTop - scroll
        for ((text, color) in lines) {
            context.drawText(textRenderer, trim(text), contentX, cy, color, false)
            cy += LINE + 4
        }
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
        context.drawText(textRenderer, label, contentX, y, SeamPalette.LAPIS, false)
        context.fill(contentX, y + 10, contentR, y + 11, SeamPalette.BORDER)
        return y + LINE + 4
    }

    private fun drawResource(context: DrawContext, projectId: Int, resource: ResourceDto, y: Int) {
        // Manual counts (MCO-272) win while they exist — they're what the player has been editing
        // in-game. Otherwise show what Seam has. Reconciling the two properly is MCO-263, and
        // pushing local counts back is MCO-269.
        val manual = WorldDataStore.current.count(projectId, resource.itemId)
        val have = if (manual > 0) manual else resource.collected
        val complete = have >= resource.required
        val progress = if (resource.required == 0) 1f else (have.toFloat() / resource.required).coerceIn(0f, 1f)
        // Top line: name (left), bar, count (right) share a vertical center within TOP_H so the 6px
        // bar and the text baseline line up; the batch button row follows below.
        val centerY = y + TOP_H / 2
        val textY = centerY - textRenderer.fontHeight / 2
        val barTop = centerY - 3
        val name = textRenderer.trimToWidth(resource.name, 92)
        context.drawText(textRenderer, name, contentX, textY, if (complete) SeamPalette.DISABLED else SeamPalette.INK, false)
        val barX = contentX + 96
        val barW = 84
        context.fill(barX, barTop, barX + barW, barTop + 6, SeamPalette.TRACK)
        val fw = (barW * progress).toInt()
        if (fw > 0) context.fill(barX, barTop, barX + fw, barTop + 6, if (complete) SeamPalette.GREEN else SeamPalette.LAPIS)
        val count = "$have / ${resource.required}"
        context.drawText(
            textRenderer, count, contentR - textRenderer.getWidth(count), textY,
            if (complete) SeamPalette.GREEN else SeamPalette.INK, false,
        )
    }

    private fun drawTask(context: DrawContext, task: TaskDto, y: Int) {
        val mark = if (task.completed) "[x] " else "[ ] "
        val color = if (task.completed) SeamPalette.DISABLED else SeamPalette.INK
        context.drawText(textRenderer, trim(mark + task.name), contentX, y, color, false)
    }

    private fun projectLabel(): String {
        val current = project ?: return "No project"
        val position = if (projects.size > 1) " (${projectIndex + 1}/${projects.size})" else ""
        return textRenderer.trimToWidth("Project: ${current.name}$position", contentR - contentX - 70)
    }

    /**
     * The status line describes the *selected project*. With no project there is nothing to say
     * here — the body already explains why and what to do about it, and the footer carries sync
     * state, so anything else would be the same sentence twice.
     */
    private fun statusLine(): String =
        project?.let { "Stage: ${it.stage.pretty()} - ${it.state.pretty()}" }.orEmpty()

    private fun syncLine(data: SeamData): String = when (data) {
        is SeamData.Loaded -> "Last synced: ${TIME.format(Date(data.fetchedAtMillis))}"
        is SeamData.Loading -> "Syncing..."
        else -> "Last synced: never"
    }

    /** `IN_PROGRESS` -> `In progress`. The API sends enum names; the notebook shouldn't shout. */
    private fun String.pretty(): String =
        lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Signed label for a batch button: "+1", "+64", "+1728", "-1", "-64". */
    private fun batchLabel(delta: Int): String = if (delta >= 0) "+$delta" else "$delta"

    /** Keep long lines inside the panel. */
    private fun trim(text: String): String = textRenderer.trimToWidth(text, contentR - contentX)

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(null)
    }

    private companion object {
        const val PAD = 14
        const val LINE = 10
        const val BTN = 20
        const val TOP_H = 13 // top line height (name / bar / count) above the batch button row
        const val BATCH_BTN = 16 // shorter height for the batch [-1]...[+1728] buttons
        const val BTN_PAD_X = 5 // horizontal padding added to each batch button's label width
        const val BTN_GAP = 3 // gap between adjacent batch buttons
        val BATCH_DELTAS = intArrayOf(-1, -64, 1, 64, 1728)
        const val RES_ROW = 34 // TOP_H (13) + BATCH_BTN (16) + trailing pad
        const val TASK_ROW = 12
        const val GAP = 6
        const val FOOTER_H = 26
        const val SCROLL_STEP = 14

        val TIME = SimpleDateFormat("HH:mm:ss")
    }
}
