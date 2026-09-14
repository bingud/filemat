const STORAGE_PREFIX = `fm-share-token:`

function storageKey(shareId: string) {
    return `${STORAGE_PREFIX}${shareId}`
}

export function getStoredShareToken(shareId: string): string | null {
    if (!shareId) return null
    try {
        return localStorage.getItem(storageKey(shareId))
    } catch {
        return null
    }
}

export function setStoredShareToken(shareId: string, token: string) {
    if (!shareId || !token) return
    try {
        localStorage.setItem(storageKey(shareId), token)
    } catch {}
}

export function clearStoredShareToken(shareId: string) {
    if (!shareId) return
    try {
        localStorage.removeItem(storageKey(shareId))
    } catch {}
}
