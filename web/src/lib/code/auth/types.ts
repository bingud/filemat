type Principal = {
    userId: ulid,
    email: string,
    username: string,
    mfaTotpStatus: boolean,
    isBanned: boolean,
    roles: ulid[]
}

type Role = {
    roleId: ulid,
    name: string,
    createdDate: number,
    permissions: Permission[]
}

enum Permission {
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