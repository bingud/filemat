import { handleException, parseJson } from "../util/codeUtil.svelte"

export const auth: Principal | null = $state(null)


export async function auth_load(): Promise<typeof auth> {
    try {
        console.log(`Loading auth state.`)

        const body = new FormData()
        body.append("principal", "true")
        body.append("roles", "true")

        const response = await fetch(`/api/v1/auth/state`, {
            method: "POST", credentials: "same-origin", body: body
        })
        const status = response.status
        const text = await response.text()
        const json = parseJson(text)

        console.log(status)
        console.log(text)
        console.log(json)
            
        if (status === 200) {
            const data = json as { principal: { value: Principal }, roles: { value: Role[] } }
        } else {
            const error = json as ErrorResponse
        }
    } catch (e) {
        handleException("Exception when fetching principal", "Failed to load your account.", e)
    }
}