export async function postToServiceWorker(message: Record<string, unknown>): Promise<void> {
    if (typeof navigator === `undefined` || !navigator.serviceWorker) return
    try {
        const registration = await navigator.serviceWorker.ready
        const worker = navigator.serviceWorker.controller ?? registration.active
        worker?.postMessage(message)
    } catch {
        // Service worker may be unavailable.
    }
}

export async function notifyServiceWorkerLastUserId(userId: string | null | undefined): Promise<void> {
    await postToServiceWorker({
        type: `lastUserId`,
        lastUserId: userId ? `${userId}` : ``,
    })
}
