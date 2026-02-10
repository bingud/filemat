import { PermissionType, type AnyPermission, type FilePermission, type SystemPermission } from "../auth/types";


export type PermissionMeta<T> = { id: T, name: string, description: string, type: PermissionType, level: number }
export const unknownPermission = { name: "Unknown permission", description: "No description (unknown permission)", type: 0}

export const systemPermissionMeta: Record<SystemPermission, PermissionMeta<SystemPermission>> = {
    "MANAGE_OWN_FILE_PERMISSIONS": { id: "MANAGE_OWN_FILE_PERMISSIONS", name: "Manage own file permissions", description: "User can manage their own file permissions", type: PermissionType.system, level: 1},
    "EXPOSE_FOLDERS": { id: "EXPOSE_FOLDERS", name: "Expose folders", description: "Manage exposed folders", type: PermissionType.system, level: 1},
    "ACCESS_ALL_FILES": { id: "ACCESS_ALL_FILES", name: "Access all files", description: "Read all files in the system", type: PermissionType.system, level: 1},
    "MANAGE_ALL_FILE_PERMISSIONS": { id: "MANAGE_ALL_FILE_PERMISSIONS", name: "Manage all file permissions", description: "Manage file permissions for all files", type: PermissionType.system, level: 2},
    "MANAGE_USERS": { id: "MANAGE_USERS", name: "Manage users", description: "Manage user accounts", type: PermissionType.system, level: 3},
    "EDIT_ROLES": { id: "EDIT_ROLES", name: "Edit roles", description: "Edit any role", type: PermissionType.system, level: 3},
    "MANAGE_SYSTEM": { id: "MANAGE_SYSTEM", name: "Manage system", description: "Manage the system", type: PermissionType.system, level: 3},
    "SUPER_ADMIN": { id: "SUPER_ADMIN", name: "Super admin", description: "Has all permissions to manage the entire system", type: PermissionType.system, level: 4},
    "MANAGE_ALL_FILE_SHARES": { id: "MANAGE_ALL_FILE_SHARES", name: "Manage all file shares", description: "Manage all shares of a file", type: PermissionType.system, level: 1},
    "CHANGE_OWN_HOME_FOLDER": { id: "CHANGE_OWN_HOME_FOLDER", name: "Set own home folder", description: "User can set the path of their own home folder.", type: PermissionType.system, level: 1},
}
export const systemPermissionCount = Object.keys(systemPermissionMeta).length

export const filePermissionMeta: Record<FilePermission, PermissionMeta<FilePermission>> = {
    "READ": { id: "READ", name: "Read", description: "Read a file", type: PermissionType.file, level: 0},
    "DELETE": { id: "DELETE", name: "Delete", description: "Delete a file", type: PermissionType.file, level: 0},
    "WRITE": { id: "WRITE", name: "Write", description: "Save or edit a file", type: PermissionType.file, level: 0},
    "SHARE": { id: "SHARE", name: "Share", description: "Publicly share a file", type: PermissionType.file, level: 0},
    "RENAME": { id: "RENAME", name: "Rename", description: "Rename a file", type: PermissionType.file, level: 0},
    "MOVE": { id: "MOVE", name: "Move", description: "Move a file", type: PermissionType.file, level: 0},
}
export const filePermissionCount = Object.keys(filePermissionMeta).length