import { PermissionType, type Permission } from "../auth/types";


export type PermissionMeta = { name: string, description: string, type: PermissionType }
const permissionMeta: Record<Permission, PermissionMeta> = {
    "READ": { name: "Read", description: "Read a file", type: PermissionType.file},
    "DELETE": { name: "Delete", description: "Delete a file", type: PermissionType.file},
    "WRITE": { name: "Write", description: "Save or edit a file", type: PermissionType.file},
    "SHARE": { name: "Share", description: "Publicly share a file", type: PermissionType.file},
    "RENAME": { name: "Rename", description: "Rename a file", type: PermissionType.file},
    "MANAGE_OWN_FILE_PERMISSIONS": { name: "Manage own file permissions", description: "User can manage their own file permissions", type: PermissionType.system},
    "ACCESS_ALL_FILES": { name: "Access all files", description: "Read all files in the system", type: PermissionType.system},
    "MANAGE_ALL_FILE_PERMISSIONS": { name: "Manage all file permissions", description: "Manage file permissions for all files", type: PermissionType.system},
    "MANAGE_USERS": { name: "Manage users", description: "Manage user accounts", type: PermissionType.system},
    "MANAGE_SYSTEM": { name: "Mange system", description: "Manage the system", type: PermissionType.system},
    "EDIT_ROLES": { name: "Edit roles", description: "Edit any role", type: PermissionType.system},
    "EXPOSE_FOLDERS": { name: "Expose folders", description: "Manage exposed folders", type: PermissionType.system},
}

const unknownPermission = { name: "Unknown permission", description: "No description (unknown permission)", type: 0}


export function formatPermission(p: Permission): string {
    return permissionMeta[p]?.name ?? "Unknown permission"
}

export function getPermissionInfo(p: Permission): PermissionMeta {
    return permissionMeta[p] ?? unknownPermission
}