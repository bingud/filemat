import { auth } from "../stateObjects/authState.svelte"
import { handleError, handleErrorResponse, handleException, isServerDown, parseJson } from "../util/codeUtil.svelte"
import { appState } from "../stateObjects/appState.svelte"


/**
 * Fetches general application state, such as auth, available roles, app state.
 */
export async function fetchState(options: { principal: boolean, roles: boolean, app: boolean }): Promise<boolean> {
    try {
        const body = new FormData()
        if (options.principal) body.append("principal", "true")
        if (options.roles) body.append("roles", "true")
        if (options.app) body.append("app", "true")

        const response = await fetch(`/api/v1/state/select`, {
            method: "POST", credentials: "same-origin", body: body
        })
        const status = response.status
        const text = await response.text()
        const json = parseJson(text)

        if (status === 200) {
            const data = json as { principal: { value: Principal, status: HttpStatus }, roles: { value: Role[], status: HttpStatus }, app: { value: { isSetup: boolean }, status: HttpStatus } }

            if (options.principal) {
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
            if (options.roles) {
                const status = data.roles.status

                if (status === 200) {
                    const roleList = data.roles.value
                    auth.roleList = roleList
                } else if (status === 401) {
                    auth.roleList = null
                } else {
                    handleError(`Status ${status} for role list when fetching state.`, `Failed to load the list of user roles (${status})`)
                    return false
                }
            }
            if (options.app) {
                const status = data.app.status

                if (status === 200) {
                    const app = data.app.value
                    appState.isSetup = app.isSetup
                } else {
                    handleError(`Status ${status} for app state when fetching state.`, `Failed to load Filemat state (${status})`)
                    return false
                }
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