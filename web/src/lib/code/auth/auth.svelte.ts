import { handleErrorResponse, handleException, parseJson } from "../util/codeUtil.svelte"

class AuthState {
    principal: Principal | null = $state(null)
    roleList: Role[] | null = $state(null)
    authenticated: boolean | null = $state(false)

    constructor() {
        this.reset = this.reset.bind(this)
    }

    reset() {
        this.principal = null
        this.roleList = null
        this.authenticated = null
        console.log(`Auth state wiped.`)
    }
}

export const auth = new AuthState()

///

export async function auth_load(): Promise<Principal | null> {
    try {
        const body = new FormData()
        body.append("principal", "true")
        body.append("roles", "true")

        const response = await fetch(`/api/v1/auth/state`, {
            method: "POST", credentials: "same-origin", body: body
        })
        const status = response.status
        const text = await response.text()
        const json = parseJson(text)

        if (status === 200) {
            const data = json as { principal: { value: Principal }, roles: { value: Role[] } }
            const principal = data.principal.value
            const roleList = data.roles.value

            auth.principal = principal
            auth.roleList = roleList
            auth.authenticated = true

            console.log(`Loaded auth state.`)
            return principal
        } else {
            if (status === 401) {
                auth.authenticated = false
                return null
            }

            const error = json as ErrorResponse
            handleErrorResponse(error, "Failed to load your account.")
            return null
        }
    } catch (e) {
        handleException("Exception when fetching principal", "Failed to load your account.", e)
        return null
    }
}