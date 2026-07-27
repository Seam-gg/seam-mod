package gg.seam.mod.screen

/**
 * The "Daylight Field Notebook" tokens, shared by every Seam screen. Values are ported from
 * `mc-org`'s `design-tokens.css` — the workspace's single source of truth. Never eyeball a colour
 * here; port the token.
 *
 * All values are **ARGB**: `DrawContext` ignores components with a zero alpha byte, so text drawn
 * with a bare `0xRRGGBB` is invisible.
 */
object SeamPalette {
    const val PANEL = 0xFFFBF7EC.toInt()
    const val BORDER = 0xFF8A8069.toInt()
    const val INK = 0xFF2E2A24.toInt()
    const val MUTED = 0xFF7A7263.toInt()
    const val LAPIS = 0xFF2B5B8C.toInt()
    const val GREEN = 0xFF2F8F5B.toInt()
    const val RED = 0xFFB4443C.toInt()
    const val TRACK = 0xFFD8CDBA.toInt()
    const val DISABLED = 0xFFB4AC9C.toInt()
}
