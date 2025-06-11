import { auth } from "$lib/code/stateObjects/authState.svelte"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { delay, handleError, handleErrorResponse, handleException, isServerDown, parseJson, unixNow } from "../util/codeUtil.svelte"
import type { HttpStatus, Principal, Role } from "../auth/types"
import type { ErrorResponse, ulid } from "../types/types"
import { clientState } from "../stateObjects/clientState.svelte"


type state = {
    principal: { value: Principal, status: HttpStatus }, 
    roles: { value: Role[], status: HttpStatus }, 
    app: { value: { isSetup: boolean, followSymlinks: boolean }, status: HttpStatus },
    systemRoleIds: { value: { user: ulid, admin: ulid }, status: HttpStatus },
    hashCode: number,
}

/**
 * Fetches general application state, such as auth, available roles, app state.
 */
export async function fetchState(
    options: { principal: boolean, roles: boolean, app: boolean, systemRoleIds: boolean, followSymlinks: boolean } | undefined = undefined,
    stateHashCode: number | undefined = undefined,
): Promise<boolean> {
    try {
        const body = new FormData()
        if (!options || options.principal) body.append("principal", "true")
        if (!options || options.roles) body.append("roles", "true")
        if (!options || options.app) body.append("app", "true")
        if (!options || options.systemRoleIds) body.append("systemRoleIds", "true")
        if (!options || options.followSymlinks) body.append("followSymlinks", "true")
        if (stateHashCode) body.append("rawStateHashCode", stateHashCode.toString())

        const response = await fetch(`/api/v1/state/select`, {
            method: "POST", credentials: "same-origin", body: body
        })
        const status = response.status
        const text = await response.text()
        if (text === "up-to-date") return true

        const json = parseJson(text)

        if (status === 200) {
            const data = json as state

            if (!options) {
                const newHashCode = data.hashCode
                if (newHashCode) {
                    appState.stateHashCode = newHashCode
                }
            }

            if (!options || options.principal) {
                const status = data.principal.status
                
                if (status === 200) {
                    const principal = data.principal.value
                    auth.principal = principal
                    auth.authenticated = true
                } else if (status === 401) {
                    auth.principal = null
                    auth.authenticated = false
                } else {
                    handleError(`Status ${status} for principal when fetching state.`, `Failed to load your account (${status})`)
                    return false
                }
            }
            if (!options || options.roles) {
                const status = data.roles.status

                if (status === 200) {
                    const roleList = data.roles.value
                    appState.roleList = roleList
                } else if (status === 401) {
                    appState.roleList = null
                } else {
                    handleError(`Status ${status} for role list when fetching state.`, `Failed to load roles (${status})`)
                    return false
                }
            }
            if (!options || options.app) {
                const status = data.app.status

                if (status === 200) {
                    const app = data.app.value
                    appState.isSetup = app.isSetup
                    appState.followSymlinks = app.followSymlinks
                } else {
                    handleError(`Status ${status} for app state when fetching state.`, `Failed to load Filemat state (${status})`)
                    return false
                }
            }
            if (!options || options.systemRoleIds) {
                const status = data.systemRoleIds.status

                if (status === 200) {
                    const ids = data.systemRoleIds.value
                    appState.systemRoleIds = ids
                } else {
                    handleError(`Status ${status} for system role IDs when fetching state.`, `Failed to load system roles (${status})`)
                }
            }

            if (!options) {
                appState.lastFullStateRefresh = unixNow()
            }

            console.log(`Loaded state.`)
            return true
        } else if (isServerDown(status)) {
            handleError(`Server is ${status} while fetching state`, "Server is unavailable.")
            return false
        } else {
            const error = json as ErrorResponse
            handleErrorResponse(error, `Failed to load state (${status})`)
            return false
        }
    } catch (e) {
        handleException("Exception when fetching state", "An error occurred while loading state.", e)
        return false
    }
}


let autoSyncRunning = false
/**
 * Re-fetches the application state o
 */
export async function startStateAutoSync() {
    if (autoSyncRunning) return
    autoSyncRunning = true
    
    while (true) {
        try {
            // Re-fetch state every 20 seconds, unless idle (then 60 seconds)
            await delay(clientState.isIdle ? 20_000 : 60_000)

            await fetchState(undefined, appState.stateHashCode || undefined)
        } catch (e) {}
    }
}