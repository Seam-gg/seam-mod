package gg.seam.mod.screen

import gg.seam.mod.api.SeamApi
import gg.seam.mod.auth.DeviceCodeAuth
import gg.seam.mod.util.Browser
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Settings, currently just the **Account** section — the device-code link flow (MCO-267).
 *
 * The remaining sections from the v1 spec (Sync, Display, Containers, Notifications, Scanning) and
 * the tab bar that holds them are the settings-skeleton issue; this screen is deliberately the
 * account surface that flow needs, not a half-built version of that one.
 *
 * The link state changes on background threads, so the status text and the primary button's label
 * are recomputed every frame from [DeviceCodeAuth.state] rather than pushed on transitions.
 */
class SettingsScreen(private val parent: Screen?) : Screen(Text.literal("Seam Settings")) {

    private lateinit var primaryButton: ButtonWidget
    private lateinit var openPageButton: ButtonWidget
    private lateinit var copyCodeButton: ButtonWidget
    private lateinit var closeButton: ButtonWidget

    /** Unlink is destructive, so the button asks once before doing it (spec: "confirms first"). */
    private var confirmingUnlink = false

    private var left = 0
    private var top = 0
    private var panelW = 0
    private var panelH = 0
    private var contentX = 0
    private var contentR = 0

    override fun init() {
        panelW = minOf(360, width - 20)
        panelH = minOf(200, height - 20)
        left = (width - panelW) / 2
        top = (height - panelH) / 2
        contentX = left + PAD
        contentR = left + panelW - PAD

        primaryButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("")) { onPrimary() }
                .dimensions(contentX, top + 96, 150, BTN).build(),
        )
        // Screen text isn't clickable (no hit-testing on drawText), so the verification page gets a
        // real button rather than a URL the player has to retype.
        openPageButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Open page")) { openVerificationPage() }
                .dimensions(contentX + 156, top + 96, 80, BTN).build(),
        )
        copyCodeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Copy code")) { copyCode() }
                .dimensions(contentX + 242, top + 96, 80, BTN).build(),
        )
        closeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Back")) { close() }
                .dimensions(contentR - 54, top + panelH - BTN - 8, 54, BTN).build(),
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Do NOT call renderBackground() — the framework applies the backdrop pre-render, and the
        // blur may only run once per frame (see docs/fabric-1.21.11-reference.md §4).
        val state = DeviceCodeAuth.state

        // panel + border
        context.fill(left, top, left + panelW, top + panelH, SeamPalette.PANEL)
        context.fill(left, top, left + panelW, top + 1, SeamPalette.BORDER)
        context.fill(left, top + panelH - 1, left + panelW, top + panelH, SeamPalette.BORDER)
        context.fill(left, top, left + 1, top + panelH, SeamPalette.BORDER)
        context.fill(left + panelW - 1, top, left + panelW, top + panelH, SeamPalette.BORDER)

        context.drawText(textRenderer, "SETTINGS", contentX, top + 12, SeamPalette.INK, false)

        var y = top + 32
        context.drawText(textRenderer, "ACCOUNT", contentX, y, SeamPalette.LAPIS, false)
        context.fill(contentX, y + 10, contentR, y + 11, SeamPalette.BORDER)
        y += LINE + 6

        for ((text, color) in statusLines(state)) {
            context.drawText(textRenderer, trim(text), contentX, y, color, false)
            y += LINE + 2
        }

        primaryButton.message = Text.literal(primaryLabel(state))
        val awaiting = state is DeviceCodeAuth.State.AwaitingApproval
        for (button in listOf(openPageButton, copyCodeButton)) {
            button.visible = awaiting
            button.active = awaiting
        }

        for (element in children()) (element as? Drawable)?.render(context, mouseX, mouseY, delta)

        context.drawText(
            textRenderer,
            trim("Endpoint: ${SeamApi.baseUrl()}"),
            contentX,
            top + panelH - BTN - 4,
            SeamPalette.MUTED,
            false,
        )
    }

    /** Status block for the current state, as (text, ARGB colour) pairs. */
    private fun statusLines(state: DeviceCodeAuth.State): List<Pair<String, Int>> = when (state) {
        is DeviceCodeAuth.State.Idle -> listOf(
            "Not linked." to SeamPalette.MUTED,
            "Link your Seam account to pull projects and push progress." to SeamPalette.MUTED,
        )

        is DeviceCodeAuth.State.Starting -> listOf("Requesting a code from Seam…" to SeamPalette.INK)

        is DeviceCodeAuth.State.AwaitingApproval -> listOf(
            "Code: ${state.userCode}" to SeamPalette.LAPIS,
            "Open ${state.verificationUri} and enter it." to SeamPalette.INK,
            "Waiting for approval — expires in ${secondsLeft(state.expiresAtMillis)}s." to SeamPalette.MUTED,
        )

        is DeviceCodeAuth.State.Linked -> listOf(
            "Linked as ${state.username.ifBlank { "your Seam account" }}." to SeamPalette.GREEN,
        )

        is DeviceCodeAuth.State.Failed -> listOf(
            state.reason to SeamPalette.RED,
            "Try linking again." to SeamPalette.MUTED,
        )
    }

    private fun primaryLabel(state: DeviceCodeAuth.State): String = when {
        state is DeviceCodeAuth.State.Linked && confirmingUnlink -> "Confirm unlink"
        state is DeviceCodeAuth.State.Linked -> "Unlink account"
        state is DeviceCodeAuth.State.Starting || state is DeviceCodeAuth.State.AwaitingApproval -> "Cancel"
        else -> "Link account"
    }

    private fun onPrimary() {
        when (DeviceCodeAuth.state) {
            is DeviceCodeAuth.State.Linked ->
                if (confirmingUnlink) {
                    confirmingUnlink = false
                    DeviceCodeAuth.unlink()
                } else {
                    confirmingUnlink = true
                }

            is DeviceCodeAuth.State.Starting, is DeviceCodeAuth.State.AwaitingApproval -> DeviceCodeAuth.cancel()

            else -> {
                confirmingUnlink = false
                DeviceCodeAuth.start()
            }
        }
    }

    private fun copyCode() {
        val state = DeviceCodeAuth.state as? DeviceCodeAuth.State.AwaitingApproval ?: return
        runCatching { client?.keyboard?.setClipboard(state.userCode) }
    }

    private fun openVerificationPage() {
        val state = DeviceCodeAuth.state as? DeviceCodeAuth.State.AwaitingApproval ?: return
        Browser.open(state.verificationUri)
    }

    private fun secondsLeft(expiresAtMillis: Long): Long =
        ((expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)

    /** Keep long lines (verification URLs, error text) inside the panel. */
    private fun trim(text: String): String = textRenderer.trimToWidth(text, contentR - contentX)

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    private companion object {
        const val PAD = 14
        const val LINE = 10
        const val BTN = 20
    }
}
