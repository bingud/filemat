import type { ulid } from "../types/types"

export type FileMetadata = {
    path: string,
    filename: string | undefined,
    modifiedDate: number,
    createdDate: number,
    fileType: FileType,
    size: number
}

export type FullFileMetadata = FileMetadata & {
    permissions: FilePermission[]
}

export type FileType =
    | "FILE" 
    | "FOLDER" 
    | "FILE_LINK" 
    | "FOLDER_LINK" 
    | "ANY_LINK" 
    | "OTHER"

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
    permissions: SystemPermission[]
}

export type SystemPermission =
  | "ACCESS_ALL_FILES"
  | "MANAGE_OWN_FILE_PERMISSIONS"
  | "MANAGE_ALL_FILE_PERMISSIONS"
  | "MANAGE_USERS"
  | "MANAGE_SYSTEM"
  | "EDIT_ROLES"
  | "EXPOSE_FOLDERS"
  | "SUPER_ADMIN";

export type FilePermission =
  | "READ"
  | "DELETE"
  | "WRITE"
  | "SHARE"
  | "RENAME";

export type AnyPermission = SystemPermission | FilePermission;


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

export type EntityPermission = {
    permissionId: ulid,
    permissionType: "USER" | "ROLE",
    entityId: ulid,
    userId: ulid | null,
    roleId: ulid | null,
    permissions: FilePermission[],
    createdDate: number,
}