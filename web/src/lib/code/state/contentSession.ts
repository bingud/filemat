import { formData, safeFetch } from "$lib/code/util/codeUtil.svelte"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { auth } from "$lib/code/stateObjects/authState.svelte"
import { joinContentUrl, notifyServiceWorkerContentBaseUrl } from "$lib/code/util/contentUrl"

export const CONTENT_COOKIE_MAX_AGE_MS = 60 * 60 * 1000
const RENEW_AFTER_MS = CONTENT_COOKIE_MAX_AGE_MS / 2

let lastContentCookieRenewal = 0
let renewing: Promise<boolean> | null = null

export async function maybeRenewContentSession(force: boolean = false): Promise<boolean> {
    const base = appState.contentBaseUrl
    if (!base) {
        lastContentCookieRenewal = 0
        notifyServiceWorkerContentBaseUrl()
        return true
    }

    notifyServiceWorkerContentBaseUrl()

    // Unauthenticated clients use shareToken (or relative URLs). Logged-in users
    // still establish the content cookie, including when they open a shared file.
    if (!auth.authenticated) {
        return true
    }

    const now = Date.now()
    if (!force && lastContentCookieRenewal > 0 && now - lastContentCookieRenewal < RENEW_AFTER_MS) {
        return true
    }

    if (renewing) return renewing

    renewing = renewContentSession()
    try {
        return await renewing
    } finally {
        renewing = null
    }
}

async function renewContentSession(): Promise<boolean> {
    const sessionUrl = joinContentUrl(`/api/v1/auth/content-session`)
    const existing = await safeFetch(sessionUrl, { credentials: `include` }, false, { skipContentAuthRetry: true })
    if (!existing.failed && existing.ok) {
        lastContentCookieRenewal = Date.now()
        return true
    }

    if (existing.failed || existing.status === 401) {
        const ticketResponse = await safeFetch(`/api/v1/auth/content-session-ticket`, {}, false, { skipContentAuthRetry: true })
        if (ticketResponse.failed || ticketResponse.status !== 200) return false

        const ticket = ticketResponse.content
        const body = formData({ ticket })
        const created = await safeFetch(sessionUrl, { body, credentials: `include` }, false, { skipContentAuthRetry: true })
        if (!created.failed && created.ok) {
            lastContentCookieRenewal = Date.now()
            return true
        }
    }

    return false
}

export function resetContentSessionRenewal() {
    lastContentCookieRenewal = 0
}
