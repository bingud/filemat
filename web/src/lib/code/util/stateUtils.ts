import type { Role } from "../auth/types";
import { config, type previewSize } from "../config/values";
import { setPreferenceSetting } from "../module/settings";
import { appState } from "../stateObjects/appState.svelte";
import { clientState } from "../stateObjects/clientState.svelte";
import { filesState } from "../stateObjects/filesState.svelte";
import { confirmDialogState, folderSelectorState, inputDialogState } from "../stateObjects/subState/utilStates.svelte";
import type { ulid } from "../types/types";
import { debounceFunction } from "./codeUtil.svelte";


export function getRole(id: ulid): Role | null {
    if (!appState.roleList) return null
    const role = appState.roleList.find((v) => v.roleId === id)
    return role ?? null
}

export function mapRoles(roleIds: ulid[]): Role[] | null {
    if (!appState.roleListObject) return null
    const list: (Role | null)[] = roleIds.map(v => appState.roleListObject![v])
    if (list.includes(null)) return null
    return list as Role[]
}

export function setPreviewSize(size: previewSize) {
    const isRows = filesState.ui.fileViewType === "rows"

    if (isRows) {
        setPreferenceSetting("preview_size_rows", size.toString())
        appState.settings.previewSize.rows = size
    } else {
        setPreferenceSetting("preview_size_grid", size.toString())
        appState.settings.previewSize.grid = size
    }
}

/**
 * Detects when the user is idle
 */
export function onUserIdleChange(
    callbacks: {
        onIdle: () => void
        onActive: () => void
        throttleInterval?: number
    },
): () => void {
    const {
        onIdle,
        onActive,
        throttleInterval = 200,
    } = callbacks

    if (typeof window === "undefined" || typeof document === "undefined") {
        return () => {}
    }

    let timeoutId: number | null = null

    const getIdleTimeout = () => {
        return document.hidden ? 10000 : 20000 // 10s unfocused, 20s focused
    }

    const goToIdle = () => {
        clientState.isIdle = true
        onIdle()
    }

    const resetTimer = () => {
        if (timeoutId !== null) {
            clearTimeout(timeoutId)
        }

        if (clientState.isIdle) {
            clientState.isIdle = false
            onActive()
        }

        timeoutId = window.setTimeout(goToIdle, getIdleTimeout())
    }

    // Use the provided debounce function to simulate throttling
    const activityHandler = debounceFunction(
        resetTimer,
        throttleInterval, // delay
        throttleInterval, // continuousCallDuration
    )

    const activityEvents = [
        "mousemove",
        "mousedown",
        "keydown",
        "touchstart",
        "scroll",
        "wheel",
        "visibilitychange",
    ]

    activityEvents.forEach((event) => {
        window.addEventListener(event, activityHandler, { passive: true })
    })

    resetTimer() // Initial call

    const stop = () => {
        if (timeoutId !== null) {
            window.clearTimeout(timeoutId)
        }
        activityEvents.forEach((event) => {
            window.removeEventListener(event, activityHandler)
        })
    }

    return stop
}


export function isDialogOpen(): boolean {
    if (
        confirmDialogState.isOpen
        || folderSelectorState.isOpen
        || inputDialogState.isOpen
        || filesState && (
            filesState.ui.fileContextMenuPopoverOpen
            || filesState.ui.fileSortingMenuPopoverOpen
            || filesState.ui.newFilePopoverOpen
        )
    ) {        
        return true
    } else { return false }
}

export function isUserInAnyInput() {
    const el = document.activeElement as HTMLElement
    if (!el) return false

    if (el.isContentEditable) return true

    const tag = el.tagName

    // Standard form controls
    if (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        tag === 'BUTTON'
    ) {
        return true
    }

    // Other widgets that can take input (e.g. custom components with tabindex)
    if (el.getAttribute('tabindex') !== null) return true

    return false
}

export function getContentUrl(path: string, encodeParam: boolean = true): string {
    const pathParam = `path=${encodeParam ? encodeURIComponent(path) : path}`
    const shareTokenParam = filesState.getIsShared() ? `shareToken=${filesState.meta.shareToken}` : ``
    return `${config.fileContentUrlPathPrefix}?${pathParam}${shareTokenParam ? '&' : ''}${shareTokenParam}`
}

export function getZipContentUrl(path: string, encodeParam: boolean = false): string {
    const pathParam = `path=${encodeParam ? encodeURIComponent(path) : path}`
    const shareTokenParam = filesState.getIsShared() ? `shareToken=${filesState.meta.shareToken}` : ``
    return `/api/v1/file/zip-multiple-content?${pathParam}${shareTokenParam ? '&' : ''}${shareTokenParam}`
}