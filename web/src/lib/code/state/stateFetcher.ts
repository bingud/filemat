import { auth } from "../state/authState.svelte"
import { handleErrorResponse, handleException, parseJson } from "../util/codeUtil.svelte"


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
            const data = json as { principal: { value: Principal, status: HttpStatus }, roles: { value: Role[], status: HttpStatus }, app: { value: {}, status: HttpStatus } }

            if (options.principal) {
                const principal = data.principal.value
                auth.principal = principal
                auth.authenticated = true
            }
            if (options.roles) {
                const roleList = data.roles.value
                auth.roleList = roleList
            }
            if (options.app) {
                
            }

            console.log(`Loaded state.`)
            return true
        } else {
            const error = json as ErrorResponse
            handleErrorResponse(error, "Failed to load your state data.")
            return false
        }
    } catch (e) {
        handleException("Exception when fetching state", "Failed to load state data.", e)
        return false
    }
}