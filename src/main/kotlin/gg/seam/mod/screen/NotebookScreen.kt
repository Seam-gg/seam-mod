package gg.seam.mod.screen

import gg.seam.mod.api.ContainerTagDto
import gg.seam.mod.api.ProjectDto
import gg.seam.mod.api.ResourceDto
import gg.seam.mod.api.TaskDto
import gg.seam.mod.chat.SeamChat
import gg.seam.mod.data.SeamData
import gg.seam.mod.data.SeamDataStore
import gg.seam.mod.data.SeamSync
import gg.seam.mod.data.SyncState
import gg.seam.mod.data.WorldDataStore
import gg.seam.mod.tag.ContainerTagStore
import gg.seam.mod.tag.ContainerTarget
import gg.seam.mod.tag.TagPos
import gg.seam.mod.util.onClientThread
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

    /**
     * This project's tagged containers, ordered so a row and its button never disagree.
     *
     * Sorted rather than left in server order: the buttons are built once and positioned per frame
     * by index, so an unstable order would have "Untag" on row three delete row one's chest.
     */
    private val containers: List<ContainerTagDto>
        get() = project?.let { current ->
            ContainerTagStore.tags
                .filter { it.projectId == current.id }
                .sortedWith(compareBy({ it.dimension }, { it.x }, { it.y }, { it.z }))
        }.orEmpty()

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
    private lateinit var undoButton: ButtonWidget
    private lateinit var closeButton: ButtonWidget

    /**
     * Five batch buttons per resource ([-1][-64] left, [+1][+64][+1728] right), parallel to the
     * current project's resources. Y is set per-frame in render() as the body scrolls; X/width are
     * fixed in init().
     */
    private val batchButtons = mutableListOf<List<ButtonWidget>>()
    private val taskButtons = mutableListOf<ButtonWidget>()

    /** One "Untag" per tagged container of the current project — parallel to [containers]. */
    private val untagButtons = mutableListOf<ButtonWidget>()

    /**
     * Which project [batchButtons] were built for. Each button closes over its project id and item
     * id, so when a background refresh swaps the project set underneath us the buttons have to be
     * rebuilt — comparing sizes alone would miss two different projects with equal resource counts
     * and leave buttons writing counts against the previous project.
     */
    private var builtForProjectId: Int? = null

    /**
     * The notebook's three sections, one open at a time.
     *
     * An accordion rather than a tab strip, and rather than one long scroll. The long scroll was
     * the bug: a real project has 557 resources, so TASKS and CONTAINERS sat five hundred rows down
     * and might as well not have existed. A tab strip was the other candidate and mc-org removed
     * exactly that pattern in MCO-481 — though its objection was a strip carrying one real
     * destination, which does not apply to three.
     *
     * The headers are **pinned**, not scrolled with the content. That is the whole point: headers
     * that scroll away put you back to hunting for them past the resource list.
     */
    private enum class Section { RESOURCES, TASKS, CONTAINERS }

    private var expanded = Section.RESOURCES
    private val sectionButtons = linkedMapOf<Section, ButtonWidget>()

    /** How many container rows the buttons were built for, so a late pull rebuilds them. */
    private var builtForContainerCount = -1

    override fun init() {
        panelW = minOf(360, width - 20)
        panelH = minOf(340, height - 20)
        left = (width - panelW) / 2
        top = (height - panelH) / 2
        contentX = left + PAD
        contentR = left + panelW - PAD

        // Pull on open — the default sync frequency. Stale-guarded because init() also re-runs on
        // every window resize. Cheap when the token or binding is absent: refresh() short-circuits
        // to Unlinked/Unmapped without touching the network.
        SeamDataStore.refreshIfStale()
        // Tagged containers and whether anything reads them (MCO-536). Structure cadence, not the
        // count poll: asked when the notebook opens, never on a timer. Same stale guard, for the
        // same reason — init() re-runs on every window resize.
        ContainerTagStore.refreshIfStale()

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
            ButtonWidget.builder(Text.literal("Refresh")) { SeamSync.flush(); SeamDataStore.refresh() }
                .dimensions(contentR - 56, projectBtnY, 56, BTN).build(),
        )
        statusY = projectBtnY + BTN + 4

        // ---- fixed footer ----
        val footerTop = top + panelH - FOOTER_H
        syncTextY = footerTop + 8
        closeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Close")) { close() }
                .dimensions(contentR - CLOSE_W, footerTop + 2, CLOSE_W, BTN).build(),
        )
        // Scoped to the shown project, never the whole queue: the queue can hold edits from an
        // earlier offline session that the player can't see from here, and discarding those
        // silently would destroy work.
        undoButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Undo edits")) { undoProjectEdits() }
                .dimensions(contentR - CLOSE_W - 4 - UNDO_W, footerTop + 2, UNDO_W, BTN).build(),
        )

        // ---- section switcher ----
        // One row of three, not three stacked full-width buttons. Stacked, they read as three big
        // buttons rather than as a heading each, and they cost 150px of a 340px panel — which is
        // the body, the thing they exist to make room for.
        val tabY = statusY + LINE + 4
        val tabW = (contentR - contentX - TAB_GAP * (Section.entries.size - 1)) / Section.entries.size
        sectionButtons.clear()
        Section.entries.forEachIndexed { i, section ->
            sectionButtons[section] = addDrawableChild(
                ButtonWidget.builder(Text.literal(sectionLabel(section))) { openSection(section) }
                    .dimensions(contentX + i * (tabW + TAB_GAP), tabY, tabW, SECTION_BTN).build(),
            )
        }

        // ---- scrollable body ----
        // The reporter warning is pinned under the tabs, but only when there is one — see
        // [drawReporterWarning].
        bodyTop = tabY + SECTION_BTN + 4 + LINE
        bodyBottom = footerTop - 2

        buildResourceButtons()
    }

    private fun openSection(section: Section) {
        if (expanded == section) return
        expanded = section
        scroll = 0
        buildResourceButtons()
    }

    /**
     * `Resources 0/557` — a name and a count, because a third of the panel holds nothing more.
     *
     * The count is what makes the tab a decision rather than a guess: you can see there is nothing
     * under TASKS without opening it.
     */
    private fun sectionLabel(section: Section): String {
        val current = project
        return when (section) {
            Section.RESOURCES -> {
                val done = current?.resources?.count { displayedCount(current.id, it) >= it.required } ?: 0
                "Resources ${done}/${current?.resources?.size ?: 0}"
            }
            Section.TASKS -> {
                val done = current?.tasks?.count { completedOf(current.id, it) } ?: 0
                "Tasks ${done}/${current?.tasks?.size ?: 0}"
            }
            Section.CONTAINERS -> "Containers ${containers.size}"
        }
    }

    /**
     * The one line that must survive whichever tab is open: nobody is reading these containers.
     *
     * Only drawn when that is true. A healthy reporter says so inside CONTAINERS, where someone who
     * went looking will find it; a broken one has to interrupt, because the whole failure is that
     * you tag chests and nothing happens and there is no other symptom.
     */
    private fun drawReporterWarning(context: DrawContext, y: Int) {
        val status = ContainerTagStore.reporter ?: return
        if (status.connected) return
        context.drawText(
            textRenderer,
            trim("No server is reading your tagged containers - counts will not update."),
            contentX, y, SeamPalette.RED, false,
        )
    }

    /**
     * Widgets for the selected project: batch counter buttons per resource (minus cluster
     * left-aligned, plus cluster right-aligned) and a toggle per task. Counts persist through
     * [WorldDataStore], keyed project id → item id (MCO-272). 64 = a stack, 1728 = a double chest
     * of stacks — deltas match the webapp.
     */
    private fun buildResourceButtons() {
        batchButtons.forEach { row -> row.forEach { remove(it) } }
        batchButtons.clear()
        taskButtons.forEach { remove(it) }
        taskButtons.clear()

        val current = project
        builtForProjectId = current?.id
        if (current == null) {
            recomputeContentHeight()
            return
        }

        current.tasks.forEach { task ->
            taskButtons += addDrawableChild(
                ButtonWidget.builder(Text.literal(taskLabel(current.id, task))) { button ->
                    val next = !completedOf(current.id, task)
                    // Queue first, push second: the queue is what survives a crash or a lost
                    // connection, and the optimistic label reads back out of it.
                    WorldDataStore.update { it.withPendingTask(current.id, task.id, next) }
                    button.message = Text.literal(taskLabel(current.id, task))
                    SeamSync.flush()
                }.dimensions(contentX, 0, contentR - contentX, BTN).build(),
            )
        }

        current.resources.forEach { resource ->
            val displayed = { displayedCount(current.id, resource) }
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
                        // Base is what's on screen, which is the server's count until the player
                        // touches an item — otherwise the first +1 would jump to 1 rather than
                        // incrementing what Seam already knows about.
                        WorldDataStore.update { data ->
                            data.withCount(current.id, resource.itemId, (displayed() + delta).coerceAtLeast(0))
                        }
                    }.dimensions(xs[idx], 0, widths[idx], BATCH_BTN).build(),
                )
            }
        }
        untagButtons.forEach { remove(it) }
        untagButtons.clear()
        builtForContainerCount = containers.size
        for (container in containers) {
            untagButtons += addDrawableChild(
                ButtonWidget.builder(Text.literal("Untag")) { untag(container) }
                    .dimensions(contentR - UNTAG_W, 0, UNTAG_W, CONTAINER_BTN).build(),
            )
        }

        recomputeContentHeight()
    }

    /**
     * Untag from the list, without walking to the chest.
     *
     * The in-world gesture needs you standing in front of it, which is exactly what you cannot do
     * for the container you have forgotten about — and a stale tag on a chest you demolished last
     * week is the one most worth removing.
     */
    private fun untag(container: ContainerTagDto) {
        val target = ContainerTarget(
            kind = container.kind.ifBlank { "chest" },
            dimension = container.dimension,
            positions = listOf(TagPos(container.x, container.y, container.z)),
        )
        ContainerTagStore.untag(target).thenRun {
            SeamChat.success("Untagged ${container.kind} at ${container.x},${container.y},${container.z}")
            onClientThread { buildResourceButtons() }
        }
    }

    private fun recomputeContentHeight() {
        val current = project
        contentHeight = if (current == null) {
            LINE * 4
        } else {
            when (expanded) {
                Section.RESOURCES -> maxOf(1, current.resources.size) * RES_ROW
                Section.TASKS -> maxOf(1, current.tasks.size) * TASK_ROW
                // Two lines for the reporter sentence, which wraps to a second when disconnected.
                Section.CONTAINERS -> (LINE * 2 + 4) + maxOf(1, containers.size) * CONTAINER_ROW
            }
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
        // The container pull lands after init(), so the untag buttons have to be rebuilt when it
        // does — otherwise the rows render with no buttons beside them until the next resize.
        if (project?.id != builtForProjectId || containers.size != builtForContainerCount) {
            buildResourceButtons()
        }

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

        // Counts move while the notebook is open — a batch button, a sweep landing — so the header
        // text is rebuilt per frame rather than at init, the same way the project label is.
        for ((section, button) in sectionButtons) {
            button.message = Text.literal(textRenderer.trimToWidth(sectionLabel(section), button.width - 8))
            button.render(context, mouseX, mouseY, delta)
        }
        drawReporterWarning(context, bodyTop - LINE - 1)

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
        val syncColor = if (SeamSync.state is SyncState.Failed) SeamPalette.RED else SeamPalette.MUTED
        // Only this project's counts can be undone from here, so the button follows the selection.
        val undoable = project?.let { WorldDataStore.current.resourceCounts.containsKey(it.id) } == true
        undoButton.visible = undoable
        undoButton.active = undoable
        // Close pushes, so say so — otherwise the label claims less than the button does.
        closeButton.message =
            Text.literal(if (WorldDataStore.current.queuedWrites > 0) "Save & close" else "Close")
        val footerTextWidth = (if (undoable) contentR - CLOSE_W - UNDO_W - 8 else contentR - CLOSE_W - 4) - contentX
        context.drawText(
            textRenderer,
            textRenderer.trimToWidth(syncLine(data), footerTextWidth.coerceAtLeast(0)),
            contentX, syncTextY, syncColor, false,
        )
        undoButton.render(context, mouseX, mouseY, delta)
        closeButton.render(context, mouseX, mouseY, delta)
    }

    private fun drawProjectBody(
        context: DrawContext,
        current: ProjectDto,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        // A collapsed section's widgets are still children of this screen, so leaving them visible
        // would render them at last frame's coordinates and — worse — leave them clickable. An
        // invisible [+64] that still adds 64 is the kind of bug nobody reports because nobody
        // believes it.
        batchButtons.flatten().forEach { it.visible = expanded == Section.RESOURCES }
        taskButtons.forEach { it.visible = expanded == Section.TASKS }
        untagButtons.forEach { it.visible = expanded == Section.CONTAINERS }

        var cy = bodyTop - scroll
        when (expanded) {
            Section.RESOURCES -> {
                if (current.resources.isEmpty()) {
                    context.drawText(textRenderer, "No resources tracked yet.", contentX, cy, SeamPalette.MUTED, false)
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
            }

            Section.TASKS -> {
                if (current.tasks.isEmpty()) {
                    context.drawText(textRenderer, "No tasks yet.", contentX, cy, SeamPalette.MUTED, false)
                }
                current.tasks.forEachIndexed { i, _ ->
                    val button = taskButtons.getOrNull(i) ?: return@forEachIndexed
                    button.y = cy
                    button.active = cy >= bodyTop && cy + BTN <= bodyBottom
                    button.render(context, mouseX, mouseY, delta)
                    cy += TASK_ROW
                }
            }

            Section.CONTAINERS -> {
                cy = drawReporterLine(context, cy)
                val tagged = containers
                if (tagged.isEmpty()) {
                    context.drawText(
                        textRenderer,
                        trim("No containers tagged for this project."),
                        contentX, cy, SeamPalette.MUTED, false,
                    )
                }
                tagged.forEachIndexed { i, container ->
                    drawContainer(context, container, cy)
                    untagButtons.getOrNull(i)?.let { button ->
                        button.y = cy - 4
                        button.active = cy >= bodyTop && cy + CONTAINER_BTN <= bodyBottom
                        button.render(context, mouseX, mouseY, delta)
                    }
                    cy += CONTAINER_ROW
                }
            }
        }
    }

    /**
     * Inside CONTAINERS: what to *do* about the reporter, not that there is a problem.
     *
     * The alarm is already pinned above the tabs by [drawReporterWarning] and repeating it here
     * would waste the one line that could say something useful. So this carries the fix instead,
     * and the two states have different ones — a world with no token needs someone in world
     * settings, a token nobody has used needs someone at a server console.
     *
     * The reassurance matters as much as the diagnosis. Told only that nothing is reading these,
     * the sensible response is to stop tagging — when in fact every tag is picked up the moment a
     * reporter connects.
     */
    private fun drawReporterLine(context: DrawContext, y: Int): Int {
        val status = ContainerTagStore.reporter
        val (text, colour) = when {
            status == null -> "Checking whether a server is reading these..." to SeamPalette.MUTED
            !status.configured ->
                "No reporter token for this world - mint one in Seam world settings." to SeamPalette.RED
            !status.connected ->
                "${status.serverName ?: "A server"} has never connected - run /seam connect on it." to
                    SeamPalette.RED
            else ->
                "Read by ${status.serverName ?: "a server"}, last seen ${status.lastSeenAt.shortTime()}." to
                    SeamPalette.GREEN
        }
        context.drawText(textRenderer, trim(text), contentX, y, colour, false)

        if (status != null && !status.connected) {
            context.drawText(
                textRenderer,
                trim("Tagging still works - tags are picked up when a reporter connects."),
                contentX, y + LINE, SeamPalette.MUTED, false,
            )
            return y + LINE * 2 + 4
        }
        return y + LINE + 4
    }

    /**
     * One tagged container on one line: where it is, what it is, and whether the sweep can read it.
     *
     * Two lines apiece was a third of the panel spent on three chests. The state is right-aligned
     * against the Untag button so the eye can run down it — `missing` and `unreadable` make a count
     * *wrong* rather than merely stale, and someone scanning should find those without reading
     * every row, which is also why they are coloured rather than only worded.
     */
    private fun drawContainer(context: DrawContext, container: ContainerTagDto, y: Int) {
        val (state, colour) = when (container.state) {
            "ok" -> "seen ${container.lastSeenAt.shortTime()}" to SeamPalette.MUTED
            "missing" -> "gone" to SeamPalette.RED
            else -> "never read" to SeamPalette.LAPIS
        }

        val stateWidth = textRenderer.getWidth(state)
        val stateX = contentR - UNTAG_W - 6 - stateWidth
        context.drawText(
            textRenderer,
            textRenderer.trimToWidth(
                "${container.x}, ${container.y}, ${container.z}  ${container.kind.pretty()}",
                (stateX - contentX - 6).coerceAtLeast(0),
            ),
            contentX, y, SeamPalette.INK, false,
        )
        context.drawText(textRenderer, state, stateX, y, colour, false)
    }

    /** An ISO-8601 instant is not readable at a glance; the clock time is. */
    private fun String?.shortTime(): String {
        if (this.isNullOrBlank()) return "never"
        return substringAfter('T').take(5).ifBlank { this }
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

    /**
     * What the player should see for an item: their own un-pushed edit if they've made one,
     * otherwise Seam's number. Local entries are cleared once they reach the server, so this falls
     * back to the authoritative value on its own — and a later edit in the web app isn't shadowed
     * by a stale local copy. (Reconciling *scanned* counts is still MCO-263.)
     */
    private fun displayedCount(projectId: Int, resource: ResourceDto): Int =
        WorldDataStore.current.resourceCounts[projectId]?.get(resource.itemId) ?: resource.collected

    /** Optimistic completion: a queued toggle outranks the server's state until it's pushed. */
    private fun completedOf(projectId: Int, task: TaskDto): Boolean =
        WorldDataStore.current.pendingTask(projectId, task.id) ?: task.completed

    private fun taskLabel(projectId: Int, task: TaskDto): String {
        val mark = if (completedOf(projectId, task)) "[x] " else "[ ] "
        return textRenderer.trimToWidth(mark + task.name, contentR - contentX - 8)
    }

    private fun drawResource(context: DrawContext, projectId: Int, resource: ResourceDto, y: Int) {
        val have = displayedCount(projectId, resource)
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

    /**
     * The footer reports the *push* side when there is one, because that's the half the player has
     * unsaved work riding on. Pulls are visible enough — the numbers on screen change.
     */
    private fun syncLine(data: SeamData): String {
        val queued = WorldDataStore.current.queuedWrites
        return when (val sync = SeamSync.state) {
            is SyncState.Syncing -> "Pushing changes..."
            is SyncState.Failed -> "Offline - $queued unsaved change${plural(queued)}, will retry"
            else -> when {
                queued > 0 -> "$queued unsaved change${plural(queued)}"
                sync is SyncState.Synced -> "Saved to Seam at ${TIME.format(Date(sync.atMillis))}"
                data is SeamData.Loaded -> "Last synced: ${TIME.format(Date(data.fetchedAtMillis))}"
                data is SeamData.Loading -> "Syncing..."
                else -> "Last synced: never"
            }
        }
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    /** `IN_PROGRESS` -> `In progress`. The API sends enum names; the notebook shouldn't shout. */
    private fun String.pretty(): String =
        lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Signed label for a batch button: "+1", "+64", "+1728", "-1", "-64". */
    private fun batchLabel(delta: Int): String = if (delta >= 0) "+$delta" else "$delta"

    /** Keep long lines inside the panel. */
    private fun trim(text: String): String = textRenderer.trimToWidth(text, contentR - contentX)

    override fun shouldPause(): Boolean = false

    /** Discard this project's un-pushed counts; the rows fall back to Seam's values. */
    private fun undoProjectEdits() {
        val current = project ?: return
        WorldDataStore.update { it.withProjectReset(current.id) }
    }

    override fun close() {
        // Resource edits are batched to here rather than pushed per click: holding +1 would
        // otherwise fire a request per press. Task toggles push immediately — they're discrete.
        //
        // The screen is gone by the time this resolves, so a failure has to reach the player some
        // other way — the work isn't lost (it stays queued), but silence would read as success.
        SeamSync.flush().thenAccept { result ->
            if (result.failure != null) {
                SeamChat.error("Could not save to Seam (${result.failure}). Changes are queued and will retry.")
            }
        }
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
        const val TASK_ROW = 22
        const val CONTAINER_ROW = 14
        const val CONTAINER_BTN = 16
        const val UNTAG_W = 44
        const val GAP = 6
        const val FOOTER_H = 26
        const val CLOSE_W = 78
        const val UNDO_W = 66
        const val SCROLL_STEP = 14
        const val SECTION_BTN = 16
        const val TAB_GAP = 2

        val TIME = SimpleDateFormat("HH:mm:ss")
    }
}
