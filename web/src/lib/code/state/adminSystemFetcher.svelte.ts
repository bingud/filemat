import { handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte"

export type ContentBaseUrlState = {
    url: string
    lockedByEnv: boolean
    forUnauthenticated: boolean
    forUnauthenticatedLockedByEnv: boolean
}

export type AdminSystemState = {
    uploadFolderPath: string
    contentBaseUrl: ContentBaseUrlState
}

class AdminSystemStateStore {
    uploadFolderPath: string | null = $state(null)
    contentBaseUrl: ContentBaseUrlState = $state({
        url: ``,
        lockedByEnv: false,
        forUnauthenticated: false,
        forUnauthenticatedLockedByEnv: false,
    })
    loaded = $state(false)
    loading = $state(false)
    loadFailed = $state(false)
}

export const adminSystemState = new AdminSystemStateStore()

export async function fetchAdminSystemState(): Promise<boolean> {
    adminSystemState.loading = true
    adminSystemState.loadFailed = false
    const response = await safeFetch(`/api/v1/admin/system/state/get`, { method: `GET` })
    if (response.failed) {
        adminSystemState.loading = false
        adminSystemState.loadFailed = true
        handleErr({
            notification: `Failed to load system settings.`,
        })
        return false
    }

    if (response.code.failed) {
        adminSystemState.loading = false
        adminSystemState.loadFailed = true
        const json = response.json()
        handleErr({
            description: `Failed to load system settings.`,
            notification: json?.message || `Failed to load system settings.`,
            isServerDown: response.code.serverDown,
        })
        return false
    }

    const data = response.json() as AdminSystemState
    adminSystemState.uploadFolderPath = data.uploadFolderPath
    adminSystemState.contentBaseUrl = {
        url: data.contentBaseUrl?.url || ``,
        lockedByEnv: data.contentBaseUrl?.lockedByEnv === true,
        forUnauthenticated: data.contentBaseUrl?.forUnauthenticated === true,
        forUnauthenticatedLockedByEnv: data.contentBaseUrl?.forUnauthenticatedLockedByEnv === true,
    }
    adminSystemState.loaded = true
    adminSystemState.loading = false
    return true
}
