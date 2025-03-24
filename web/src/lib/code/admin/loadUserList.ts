import type { PublicUser } from "../auth/types";
import { safeFetch, handleError, handleErrorResponse } from "../util/codeUtil.svelte";




export async function loadUserList(): Promise<PublicUser[] | null> {
    const response = await safeFetch(`/api/v1/admin/user/list`, { method: "POST", credentials: "same-origin" })
    if (response.failed) {
        handleError(response.exception, `Failed to load list of users.`)
        return null
    }
    const status = response.code;
    const json = response.json();

    if (status.ok) {
        return json
    } else if (status.serverDown) {
        handleError(`Server ${status} when fetching user list.`, "Failed to load users. The server is unavailable.")
        return null
    } else {
        handleErrorResponse(json, `Failed to load the list of all users. (${status})`)
        return null
    }
}
