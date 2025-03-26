import type { ulid } from "../types"

export type Principal = {
    userId: ulid,
    email: string,
    username: string,
    mfaTotpStatus: boolean,
    isBanned: boolean,
    roles: ulid[]
}

export type Role = {
    roleId: ulid,
    name: string,
    createdDate: number,
    permissions: Permission[]
}

export type Permission = (
    "READ" |
    "DELETE" |
    "WRITE" |
    "SHARE" |
    "RENAME" |
    "ACCESS_ALL_FILES" |
    "MANAGE_OWN_FILE_PERMISSIONS" |
    "MANAGE_ALL_FILE_PERMISSIONS" |
    "MANAGE_USERS" |
    "MANAGE_SYSTEM" |
    "EDIT_ROLES" |
    "EXPOSE_FOLDERS" |
    "SUPER_ADMIN"
)

export enum PermissionType {
    "file" = 1,
    "system" = 2
}

export type HttpStatus = 200 | 400 | 401 | 403 | 404 | 500 | 503


export type PublicUser = {
    userId: ulid,
    email: string,
    username: string,
    mfaTotpStatus: boolean,
    createdDate: number,
    lastLoginDate: number | null,
    isBanned: boolean,
}

export type FullPublicUser = PublicUser & {
    roles: ulid[]
}

export type RoleMeta = Role & { userIds: ulid[] }

export type MiniUser = {
    userId: ulid,
    username: string
}