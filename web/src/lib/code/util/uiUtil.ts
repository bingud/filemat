import { goto } from "$app/navigation"
import { desktopWidth } from "../config/values"
import type { filesState } from "../stateObjects/filesState.svelte"
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

// Makes an element disabled for a specified time
export function disabledFor(
    node: HTMLElement & { disabled?: boolean },
    duration: number
) {
    node.disabled = true

    const timeout = setTimeout(() => {
        node.disabled = false
    }, duration)

    return {
        destroy() {
            clearTimeout(timeout)
            node.disabled = false
        }
    }
}

export function autofocus(
    node: HTMLElement,
) {
    const timeout = setTimeout(() => {
        node.focus()
    }, 5)

    return {
        destroy() {
            clearTimeout(timeout)
        }
    }
}



export function prefixSlash(node: HTMLInputElement) {
    function ensureSlashPrefix() {
        if (!node.value.startsWith('/')) {
            const cursorPos = node.selectionStart || 0
            node.value = '/' + node.value
            // Adjust cursor position to account for added slash
            node.setSelectionRange(cursorPos + 1, cursorPos + 1)
        }
    }

    function handleInput() {
        ensureSlashPrefix()
    }

    function handleKeyDown(event: KeyboardEvent) {
        const cursorPos = node.selectionStart || 0
        const isAtStart = cursorPos === 0
        const isSelectingFromStart = node.selectionStart === 0 && (node.selectionEnd || 0) > 0

        // Prevent deletion of the slash
        if ((event.key === 'Backspace' || event.key === 'Delete') && 
            (isAtStart || isSelectingFromStart)) {
            if (node.value.startsWith('/') && node.value.length === 1) {
                event.preventDefault()
            } else if (isSelectingFromStart) {
                event.preventDefault()
                // Allow deletion but keep the slash
                const newValue = '/' + node.value.slice(node.selectionEnd || 0)
                node.value = newValue
                node.setSelectionRange(1, 1)
            }
        }
    }

    function handleFocus() {
        ensureSlashPrefix()
    }

    // Initialize
    ensureSlashPrefix()

    // Add event listeners
    node.addEventListener('input', handleInput)
    node.addEventListener('keydown', handleKeyDown)
    node.addEventListener('focus', handleFocus)

    return {
        destroy() {
            node.removeEventListener('input', handleInput)
            node.removeEventListener('keydown', handleKeyDown)
            node.removeEventListener('focus', handleFocus)
        }
    }
}

export function fileViewType_saveInLocalstorage(state: typeof filesState.ui.fileViewType) {
    localStorage.setItem("fileViewType", state)
}
export function fileViewType_getFromLocalstorage(): typeof filesState.ui.fileViewType | null {
    return localStorage.getItem("fileViewType") as typeof filesState.ui.fileViewType
}