import { desktopWidth } from "../config/values"
import { uiState } from "../stateObjects/uiState.svelte"


/**
 * Detects the screen size
 */
export function updateScreenSize() {
    const width = window.innerWidth
    uiState.isDesktop = width >= desktopWidth

    uiState.onResize()
    return width
}

/**
 * Toggles dark mode
 */
export function toggleDarkMode(): boolean {
    uiState.isDark = !uiState.isDark
    return uiState.isDark
}