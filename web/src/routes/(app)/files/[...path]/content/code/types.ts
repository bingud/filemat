import type { EntityPermission, Role } from "$lib/code/auth/types"
import type { ulid } from "$lib/code/types"


export type EntityPermissionMeta = { permission: EntityPermission & { permissionType: "USER" }, username: string, role: null }
    | { permission: EntityPermission & { permissionType: "ROLE" }, username: null, role: Role }