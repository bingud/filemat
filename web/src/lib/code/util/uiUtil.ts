import { goto } from "$app/navigation"
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

/**
 * Saves whether dark mode is on
 */
export async function saveDarkModeState() {
    localStorage.setItem("dark-mode", `${uiState.isDark}`)
}

export async function loadDarkModeState() {
    const raw = localStorage.getItem("dark-mode")
    if (!raw) return
    if (raw === "false") {
        uiState.isDark = false
    } else {
        uiState.isDark = true
    }
}


export async function openSettingsSection(_section: typeof uiState.settings.section | null) {
    const section = _section ?? uiState.settings.defaultSection as any
    uiState.settings.section = section
    await goto(`/settings/${section}`)
}