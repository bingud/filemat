import type { Role } from "../auth/types";
import { appState } from "../stateObjects/appState.svelte";
import { clientState } from "../stateObjects/clientState.svelte";
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