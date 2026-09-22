import { appState } from "$lib/code/stateObjects/appState.svelte"
import { postToServiceWorker } from "$lib/code/util/serviceWorker"

export function joinWithContentBase(base: string, path: string): string {
    if (!base) {
        return path.startsWith(`/`) ? path : `/${path}`
    }
    const prefix = base.endsWith(`/`) ? base.slice(0, -1) : base
    const suffix = path.startsWith(`/`) ? path : `/${path}`
    return `${prefix}${suffix}`
}

export function joinContentUrl(path: string): string {
    return joinWithContentBase(appState.contentBaseUrl, path)
}

export function isCrossOriginUrl(url: string): boolean {
    try {
        const parsed = new URL(url, window.location.href)
        if (parsed.protocol !== `http:` && parsed.protocol !== `https:`) return false
        return parsed.origin !== window.location.origin
    } catch {
        return false
    }
}

export function credentialsForUrl(url: string): RequestCredentials {
    return isCrossOriginUrl(url) ? `include` : `same-origin`
}

export function contentBaseOrigin(): string | null {
    const base = appState.contentBaseUrl
    if (!base) return null
    try {
        return new URL(base, window.location.href).origin
    } catch {
        return null
    }
}

export function contentCrossOrigin(): "use-credentials" | undefined {
    const origin = contentBaseOrigin()
    if (!origin || typeof window === `undefined`) return undefined
    if (origin === window.location.origin) return undefined
    return `use-credentials`
}

export function notifyServiceWorkerContentBaseUrl() {
    postToServiceWorker({
        type: `contentBaseUrl`,
        origin: contentBaseOrigin(),
    })
}
