package gg.seam.mod.screen

import gg.seam.mod.api.ProjectDto
import gg.seam.mod.data.SeamData
import gg.seam.mod.data.SeamDataStore
import gg.seam.mod.tag.ContainerTagStore
import gg.seam.mod.tag.ContainerTarget
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Assign a container to a project (MCO-261).
 *
 * Opened by sneak + right-clicking a container with an empty hand, or by the tag keybind while
 * looking at one. Deliberately small and **non-pausing** (`shouldPause() = false`): this is a card
 * you open mid-build, assign, and close — not a place to spend time. Pausing would also stop the
 * world in singleplayer, which makes tagging a row of chests feel like a modal dialog per chest.
 *
 * Picking a project **moves the container silently** when it was already assigned elsewhere. There
 * is no confirmation step: the current assignment is on screen right above the list, re-tagging is
 * cheap and reversible, and a confirm would be friction on the common case.
 */
class ContainerTagScreen(
    private val target: ContainerTarget,
    private val parent: Screen?,
) : Screen(Text.literal("Tag container")) {

    private var left = 0
    private var top = 0
    private var panelW = 0
    private var panelH = 0
    private var contentX = 0
    private var contentR = 0

    private val projectButtons = mutableListOf<ButtonWidget>()
    private var untagButton: ButtonWidget? = null

    /** Set when a write finishes off-thread, so the next frame re-reads the assignment. */
    @Volatile
    private var needsRebuild = false

    override fun init() {
        panelW = minOf(360, width - 20)
        panelH = minOf(230, height - 20)
        left = (width - panelW) / 2
        top = (height - panelH) / 2
        contentX = left + PAD
        contentR = left + panelW - PAD

        addDrawableChild(
            ButtonWidget.builder(Text.literal("Close")) { close() }
                .dimensions(contentR - 54, top + panelH - BTN - 8, 54, BTN).build(),
        )

        SeamDataStore.refreshIfStale()
        ContainerTagStore.refreshIfStale()
        rebuild()
    }

    private fun rebuild() {
        projectButtons.forEach { remove(it) }
        projectButtons.clear()
        untagButton?.let { remove(it) }
        untagButton = null

        val assignment = ContainerTagStore.assignmentFor(target)
        val assignedTo = when (assignment) {
            is ContainerTagStore.Assignment.Synced -> assignment.projectId
            is ContainerTagStore.Assignment.Queued -> assignment.projectId
            null -> null
        }

        var y = top + 62
        for (project in SeamDataStore.projects.take(MAX_VISIBLE)) {
            val marker = if (project.id == assignedTo) "* " else ""
            val label = textRenderer.trimToWidth("$marker${project.name}", contentR - contentX - 24)
            projectButtons += addDrawableChild(
                ButtonWidget.builder(Text.literal(label)) { assign(project) }
                    .dimensions(contentX, y, contentR - contentX, BTN).build(),
            )
            y += BTN + 2
        }

        // Only offered when there is something to remove — an "Untag" on an untagged chest is a
        // button that can only disappoint.
        if (assignment != null && assignedTo != null) {
            untagButton = addDrawableChild(
                ButtonWidget.builder(Text.literal("Untag")) { unassign() }
                    .dimensions(contentX, top + panelH - BTN - 8, 60, BTN).build(),
            )
        }
    }

    private fun assign(project: ProjectDto) {
        ContainerTagStore.tag(target, project.id).thenRun { needsRebuild = true }
        needsRebuild = true
    }

    private fun unassign() {
        ContainerTagStore.untag(target).thenRun { needsRebuild = true }
        needsRebuild = true
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Do NOT call renderBackground() — see docs/fabric-1.21.11-reference.md §4.
        if (needsRebuild) {
            needsRebuild = false
            rebuild()
        }

        context.fill(left, top, left + panelW, top + panelH, SeamPalette.PANEL)
        context.fill(left, top, left + panelW, top + 1, SeamPalette.BORDER)
        context.fill(left, top + panelH - 1, left + panelW, top + panelH, SeamPalette.BORDER)
        context.fill(left, top, left + 1, top + panelH, SeamPalette.BORDER)
        context.fill(left + panelW - 1, top, left + panelW, top + panelH, SeamPalette.BORDER)

        context.drawText(textRenderer, "TAG CONTAINER", contentX, top + 12, SeamPalette.INK, false)

        val where = target.positions.first()
        val pair = if (target.isPair) " (double)" else ""
        context.drawText(
            textRenderer,
            trim("${target.kind}$pair at ${where.x}, ${where.y}, ${where.z}"),
            contentX, top + 28, SeamPalette.MUTED, false,
        )
        context.drawText(textRenderer, trim(statusLine()), contentX, top + 44, statusColour(), false)

        when (val data = SeamDataStore.state) {
            is SeamData.Unlinked ->
                context.drawText(textRenderer, trim("Link a Seam account first (Settings)."), contentX, top + 62, SeamPalette.RED, false)
            is SeamData.Unmapped ->
                context.drawText(textRenderer, trim("This save is not bound to a Seam world yet."), contentX, top + 62, SeamPalette.RED, false)
            is SeamData.Loading ->
                context.drawText(textRenderer, "Loading projects...", contentX, top + 62, SeamPalette.MUTED, false)
            is SeamData.Failed ->
                context.drawText(textRenderer, trim(data.reason), contentX, top + 62, SeamPalette.RED, false)
            is SeamData.Loaded -> if (data.projects.isEmpty()) {
                context.drawText(textRenderer, trim("No projects in this Seam world yet."), contentX, top + 62, SeamPalette.MUTED, false)
            }
        }

        for (element in children()) (element as? Drawable)?.render(context, mouseX, mouseY, delta)
    }

    /** The one line that says what this container is, and whether Seam knows it yet. */
    private fun statusLine(): String = when (val assignment = ContainerTagStore.assignmentFor(target)) {
        null -> if (ContainerTagStore.loaded) "Not tagged." else "Checking..."
        is ContainerTagStore.Assignment.Synced -> "Tagged to ${projectName(assignment.projectId)}."
        // Said out loud rather than shown as success: a queued tag is a promise, and the player
        // should know the difference before they walk away from the chest.
        is ContainerTagStore.Assignment.Queued ->
            if (assignment.projectId == null) "Untag queued — will send when Seam is reachable."
            else "Queued for ${projectName(assignment.projectId)} — will send when Seam is reachable."
    }

    private fun statusColour(): Int = when (ContainerTagStore.assignmentFor(target)) {
        is ContainerTagStore.Assignment.Synced -> SeamPalette.GREEN
        is ContainerTagStore.Assignment.Queued -> SeamPalette.LAPIS
        null -> SeamPalette.MUTED
    }

    private fun projectName(projectId: Int): String =
        SeamDataStore.projects.firstOrNull { it.id == projectId }?.name ?: "project $projectId"

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
