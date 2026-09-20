import { appState } from "$lib/code/stateObjects/appState.svelte"

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
    if (!url.startsWith(`http://`) && !url.startsWith(`https://`)) return false
    try {
        return new URL(url, window.location.href).origin !== window.location.origin
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
    if (typeof navigator === `undefined` || !navigator.serviceWorker) return
    const origin = contentBaseOrigin()
    const message = { type: `contentBaseUrl`, origin }
    navigator.serviceWorker.controller?.postMessage(message)
    navigator.serviceWorker.ready.then((reg) => {
        reg.active?.postMessage(message)
    }).catch(() => {})
}
