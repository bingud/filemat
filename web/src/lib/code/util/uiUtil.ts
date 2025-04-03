import { goto } from "$app/navigation"
import { desktopWidth } from "../config/values"
import { uiState, type SettingSectionId } from "../stateObjects/uiState.svelte"


/**
 * Detects the screen size
 */
export function updateScreenSize() {
    const width = window.innerWidth
    uiState.isDesktop = width >= desktopWidth
    uiState.screenWidth = width

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


export async function openSettingsSection(_section: SettingSectionId | null) {
    const section = _section ?? uiState.settings.defaultSection as any
    uiState.settings.section = section
    await goto(`/settings/${section}`)
}


export function calculateTextWidth(text: string) {
    const span = document.createElement('span');
    span.style.position = 'absolute';
    span.style.visibility = 'hidden';
    span.style.whiteSpace = 'nowrap';  // Prevent line breaks
    span.style.font = getComputedStyle(document.body).font;  // Use default body font
    span.textContent = text;

    document.body.appendChild(span);
    const width = span.getBoundingClientRect().width;
    document.body.removeChild(span);

    return width;
}

export function remToPx(rem: number) {
    return rem * parseFloat(getComputedStyle(document.documentElement).fontSize);
}