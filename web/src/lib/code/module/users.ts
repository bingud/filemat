import type { MiniUser } from "../auth/types"
import type { ulid } from "../types"
import { safeFetch, formData, handleException, handleError, handleErrorResponse } from "../util/codeUtil.svelte"



/**
 * Load list of usernames with user IDs
 */
export async function loadMiniUsers(userIds: ulid[] | null, allUsers: boolean = false): Promise<MiniUser[] | null> {
    const body: any = {}
    if (!allUsers) {
        if (!userIds) return null
        body.userIdList = JSON.stringify(userIds)
    } else {
        body.allUsers = true
    }

    const response = await safeFetch(`/api/v1/admin/user/minilist`, 
        { method: "POST", credentials: "same-origin", body: formData(body) }
    )
    if (response.failed) {
        handleException(`Failed to fetch list of user mini metadata: ${status}`, "Failed to load users with this role.", response.exception)
        return null
    }
    const st = response.code
    const json = response.json()

    if (st.ok) {
        return json
    } else if (st.serverDown) {
        handleError(`Server ${st} while fetching yser mini metadata`, `Server is unavailable.`)
        return null
    } else {
        handleErrorResponse(json, `Failed to load users with this role.`)
        return null
    }
}