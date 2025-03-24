import { PermissionType, type Permission, type Role } from "../auth/types";
import { auth } from "../stateObjects/authState.svelte";
import { filterObject } from "../util/codeUtil.svelte";
import { mapRoles } from "../util/stateUtils";


export type PermissionMeta = { id: Permission, name: string, description: string, type: PermissionType, level: number }
export const permissionMeta: Record<Permission, PermissionMeta> = {
    "READ": { id: "READ", name: "Read", description: "Read a file", type: PermissionType.file, level: 0},
    "DELETE": { id: "DELETE", name: "Delete", description: "Delete a file", type: PermissionType.file, level: 0},
    "WRITE": { id: "WRITE", name: "Write", description: "Save or edit a file", type: PermissionType.file, level: 0},
    "SHARE": { id: "SHARE", name: "Share", description: "Publicly share a file", type: PermissionType.file, level: 0},
    "RENAME": { id: "RENAME", name: "Rename", description: "Rename a file", type: PermissionType.file, level: 0},
    "MANAGE_OWN_FILE_PERMISSIONS": { id: "MANAGE_OWN_FILE_PERMISSIONS", name: "Manage own file permissions", description: "User can manage their own file permissions", type: PermissionType.system, level: 1},
    "EXPOSE_FOLDERS": { id: "EXPOSE_FOLDERS", name: "Expose folders", description: "Manage exposed folders", type: PermissionType.system, level: 1},
    "ACCESS_ALL_FILES": { id: "ACCESS_ALL_FILES", name: "Access all files", description: "Read all files in the system", type: PermissionType.system, level: 1},
    "MANAGE_ALL_FILE_PERMISSIONS": { id: "MANAGE_ALL_FILE_PERMISSIONS", name: "Manage all file permissions", description: "Manage file permissions for all files", type: PermissionType.system, level: 2},
    "MANAGE_USERS": { id: "MANAGE_USERS", name: "Manage users", description: "Manage user accounts", type: PermissionType.system, level: 3},
    "EDIT_ROLES": { id: "EDIT_ROLES", name: "Edit roles", description: "Edit any role", type: PermissionType.system, level: 3},
    "MANAGE_SYSTEM": { id: "MANAGE_SYSTEM", name: "Mange system", description: "Manage the system", type: PermissionType.system, level: 4},
}
export const systemPermissionMeta = filterObject(permissionMeta, ((k, v) => v.type === PermissionType.system ))

const unknownPermission = { name: "Unknown permission", description: "No description (unknown permission)", type: 0}


export function formatPermission(p: Permission): string {
    return permissionMeta[p]?.name ?? "Unknown permission"
}

export function getPermissionInfo(p: Permission): PermissionMeta {
    return permissionMeta[p] ?? unknownPermission
}

export function hasPermissionLevel(required: number): boolean {
    if (!auth.principal) return false
    if (required < 1) return true

    const highestPermissionLevel = getAuthPermissionLevel()
    
    if (highestPermissionLevel >= required) return true
    return false
}


export function getAuthPermissionLevel(): number {
    if (!auth.principal) return 0

    const roles = mapRoles(auth.principal.roles)
    if (!roles) return 0
    const permissions = rolesToPermissions(roles)
    const levels = permissions.map(p => p.level)
    const max = Math.max(...levels)

    return max
}

export function getMaxPermissionLevel(list: Permission[]): number {
    const permissions = list.map(v => getPermissionInfo(v))
    return Math.max(...permissions.map(v => v.level))
}


export function rolesToPermissions(roles: Role[]): PermissionMeta[] {
    const addedPermissions: Permission[] = []
    const allPermissions: PermissionMeta[] = []

    roles.forEach(r => {
        const permissions = r.permissions
        permissions.forEach(perm => {
            if (addedPermissions.includes(perm)) return
            addedPermissions.push(perm)
            
            const meta = permissionMeta[perm]
            allPermissions.push(meta)
        })
    })

    return allPermissions
}