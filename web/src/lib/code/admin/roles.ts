import type { SystemPermission } from "../auth/types"
import { appState } from "../stateObjects/appState.svelte"
import type { ulid } from "../types/types"
import { arrayRemove, formData, handleErr, handleException, removeString, safeFetch } from "../util/codeUtil.svelte"


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

    if (status.notFound) {
        handleErr({
            description: `user not found when adding role`, 
            notification: `This user was not found.`
        })
        return false
    } else if (status.failed) {
        handleErr({
            description: `Server ${status} when assigning role`,
            notification: json.message || `Failed to assign role.`,
            isServerDown: status.serverDown
        })
        return false
    }
    return true
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

    if (status.notFound) {
        handleErr({
            description: `Role not found when changing perms`, 
            notification: `This role was not found.`
        })
        return false
    } else if (status.failed) {
        handleErr({
            description: `Failed to update role permissions.`, 
            notification: json.message || `Failed to update role permissions.`,
            isServerDown: status.serverDown
        })
        return false 
    }

    if (appState.roleListObject) {
        const role = appState.roleListObject[roleId]
        role.permissions = newList
    }

    return true
}


/**
 * Deletes a role
 */
export async function deleteRole(roleId: ulid): Promise<boolean> {
    const response = await safeFetch(`/api/v1/admin/role/delete`, { body: formData({ roleId: roleId }) })
    if (response.failed) {
        handleException(`Failed to delete role`, `Failed to delete role.`, response.exception)
        return false
    }
    const status = response.code
    const json = response.json()

    if (status.notFound) {
        console.log(`Role already did not exist.`)
        return true
    } else if (!status.ok) {
        handleErr({
            description: `Failed to delete role.`,
            notification: json.message || `Failed to delete role.`,
            isServerDown: status.serverDown
        })
        return false
    }

    if (appState.roleList) {
        arrayRemove(appState.roleList, v => v.roleId === roleId)
    }

    return true
}