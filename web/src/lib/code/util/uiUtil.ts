import { goto } from "$app/navigation"
import { toast } from "@jill64/svelte-toast"
import { config } from "../config/values"
import type { filesState } from "../stateObjects/filesState.svelte"
import { uiState, type SettingSectionId } from "../stateObjects/uiState.svelte"
import { generateRandomString } from "./codeUtil.svelte"


/**
 * Detects the screen size
 */
export function updateScreenSize() {
    const width = window.innerWidth
    uiState.isDesktop = width >= config.desktopWidth
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

export function useReplaceChars(node: HTMLInputElement, replaceFn: (char: string) => string) {
    function handleInput(e: any) {
        const target = e.target as HTMLInputElement
        const oldValue = target.value
        const caretPos = target.selectionStart
        
        const newValue = oldValue.split('').map(replaceFn).join('')
        
        if (newValue !== oldValue) {
            target.value = newValue
            target.selectionStart = target.selectionEnd = caretPos
        }
    }

    node.addEventListener('input', handleInput)

    return {
        destroy() {
            node.removeEventListener('input', handleInput)
        }
    }
}

export function persistentToast_loading(text: string): () => any {
    const id = generateRandomString()
    toast.loading(text, { duration: 99999000, id: id })
    return () => { toast.remove(id) }
}

/**
 * onClick listener that triggers only when element is actually clicked
 */
export function onActualClick(node: HTMLElement, callback: () => void) {
    let startX: number
    let startY: number
    const threshold = 5

    function handleMouseDown(e: MouseEvent) {
        startX = e.clientX
        startY = e.clientY
    }

    function handleMouseUp(e: MouseEvent) {
        const diffX = Math.abs(e.clientX - startX)
        const diffY = Math.abs(e.clientY - startY)
        const hasSelection = window.getSelection()?.toString().length ?? 0 > 0

        if (diffX < threshold && diffY < threshold && !hasSelection) {
            callback()
        }
    }

    node.addEventListener("mousedown", handleMouseDown)
    node.addEventListener("mouseup", handleMouseUp)

    return {
        destroy() {
            node.removeEventListener("mousedown", handleMouseDown)
            node.removeEventListener("mouseup", handleMouseUp)
        }
    }
}