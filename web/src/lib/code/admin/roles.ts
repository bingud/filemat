import type { SystemPermission } from "../auth/types"
import { appState } from "../stateObjects/appState.svelte"
import type { ulid } from "../types/types"
import { arrayRemove, formData, handleError, handleErrorResponse, handleException, removeString, safeFetch } from "../util/codeUtil.svelte"


/**
 * Adds a role to a user
 */
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


/**
 * Changes list of permissions for a role
 */
export async function changeRolePermissions(roleId: ulid, newList: SystemPermission[]): Promise<boolean> {
    const list = JSON.stringify(newList)
    const body = formData({ roleId: roleId, newPermissionList: list })
    const response = await safeFetch(`/api/v1/admin/role/update-permissions`, { body: body })
    if (response.failed) {
        handleException(`Failed to change role permission list`, `Failed to update role permissions.`, response.exception)
        return false
    }
    const status = response.code
    const json = response.json()

    if (status.ok) {
        if (appState.roleListObject) {
            const role = appState.roleListObject[roleId]
            role.permissions = newList
        }

        return true
    } else if (status.serverDown) {
        handleError(`Server ${status} when changing role permissions`, `Failed to update role permissions. Server is unavailable.`)
    } else { 
        handleErrorResponse(json, `Failed to update role permissions.`)
    }
    return false
}


/**
 * Deletes a role
 */
export async function deleteRole(roleId: ulid): Promise<boolean> {
    const response = await safeFetch(`/api/v1/admin/role/delete`, { body: formData({ roleId: roleId }) })
    if (response.failed) {
        handleException(`Failed to delete rome`, `Failed to delete role.`, response.exception)
        return false
    }
    const status = response.code
    const json = response.json()

    if (status.ok) {
        if (appState.roleList) {
            arrayRemove(appState.roleList, (v) => v.roleId === roleId)
        }

        return true
    } else if (status.serverDown) {
        handleError(`Server ${status} when deleting role`, `Failed to delete role. Server is unavailable.`)
    } else { 
        handleErrorResponse(json, `Failed to delete role.`)
    }
    return false
}