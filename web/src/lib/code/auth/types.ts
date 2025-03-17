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

export enum Permission {
    READ = "READ",
    DELETE = "DELETE",
    WRITE = "WRITE",
    SHARE = "SHARE",
    RENAME = "RENAME",
    ACCESS_ALL_FILES = "ACCESS_ALL_FILES",
    MANAGE_OWN_FILE_PERMISSIONS = "MANAGE_OWN_FILE_PERMISSIONS",
    MANAGE_ALL_FILE_PERMISSIONS = "MANAGE_ALL_FILE_PERMISSIONS",
    MANAGE_USERS = "MANAGE_USERS",
    MANAGE_SYSTEM = "MANAGE_SYSTEM",
    EDIT_ROLES = "EDIT_ROLES",
}

export type HttpStatus = 200 | 400 | 401 | 403 | 404 | 500 | 503

export type PublicUser = {
    userId: ulid,
    email: String,
    username: String,
    mfaTotpStatus: Boolean,
    createdDate: number,
    lastLoginDate: number | null,
    isBanned: Boolean,
}