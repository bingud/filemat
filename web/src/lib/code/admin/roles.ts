import type { ulid } from "../types"
import { formData, handleError, handleErrorResponse, handleException, safeFetch } from "../util/codeUtil.svelte"


    
export async function addRoleToUser(userId: ulid, roleId: ulid): Promise<boolean> {
    const body = formData({ userId: userId, roleId: roleId })
    const response = await safeFetch(`/api/v1/admin/user-role/assign`, { body: body })
    if (response.failed) {
        handleException(`Failed to assign role to user.`, `Failed to assign role.`, response.exception)
        return false
    }
    const status = response.code
    const json = response.json()

    if (status.ok) {
        return true
    } else if (status.serverDown) {
        handleError(`Server ${status} when assigning role`, `Server is unavilable.`)
    } else {
        handleErrorResponse(json, `Failed to assign role.`)
    }
    return false
}