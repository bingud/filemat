import type { MiniUser } from "../auth/types"
import type { ulid } from "../types/types"
import { safeFetch, formData, handleErr,  } from "../util/codeUtil.svelte"



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

    const response = await safeFetch(`/api/v1/admin/user/minilist`, {
        method: "POST",
        credentials: "same-origin",
        body: formData(body)
    })
    if (response.failed) {
        handleErr({
            description: `Failed to fetch list of user mini metadata`,
            notification: `Failed to load users with this role.`,
        })
        return null
    }
    const st = response.code
    const json = response.json()

    if (st.failed) {
        handleErr({
            description: `Failed to load users with this role.`,
            notification: json.message || `Failed to load users with this role.`,
            isServerDown: st.serverDown
        })
        return null
    }

    return json
}