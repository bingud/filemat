// serviceWorker.ready never settles when the worker can't be registered, and fetchState waits for this
const READY_TIMEOUT_MS = 3000

export async function postToServiceWorker(message: Record<string, unknown>): Promise<void> {
    if (typeof navigator === `undefined` || !navigator.serviceWorker) return
    try {
        // Without a registration there is no worker, and no cache of its own to clear
        if (!await navigator.serviceWorker.getRegistration()) return
        const registration = await Promise.race([
            navigator.serviceWorker.ready,
            new Promise<null>((resolve) => setTimeout(() => resolve(null), READY_TIMEOUT_MS)),
        ])
        const worker = navigator.serviceWorker.controller ?? registration?.active
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
