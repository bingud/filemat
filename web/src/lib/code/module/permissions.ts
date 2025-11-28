import type { SystemPermission, Role } from "../auth/types";
import { systemPermissionMeta, type PermissionMeta, unknownPermission } from "../data/permissions";
import { auth } from "../stateObjects/authState.svelte";
import { includesAny } from "../util/codeUtil.svelte";
import { mapRoles, getRole } from "../util/stateUtils";


/**
 * Returns a permission metadata object
 */
export function getPermissionMeta(p: SystemPermission): PermissionMeta<SystemPermission> {
    return systemPermissionMeta[p] ?? unknownPermission;
}

/**
 * Returns whether current user has sufficient provided permission level
 */
export function hasPermissionLevel(required: number): boolean {
    if (!auth.principal) return false;
    if (required < 1) return true;

    const highestPermissionLevel = getAuthPermissionLevel();

    if (highestPermissionLevel >= required) return true;
    return false;
}

/**
 * Returns whether input permission list has a sufficient permission
 */
export function containsPermission(permissions: SystemPermission[] | null, id: SystemPermission): boolean {
    if (!permissions) return false;
    if (includesAny<SystemPermission>(permissions, [id, "SUPER_ADMIN"])) return true;
    return false;
}

/**
 * Returns whether input permission list has a sufficient permission
 */
export function hasAnyPermission(requiredPermissions: SystemPermission[], ignoreSuperAdmin: boolean = false): boolean {
    const permissions = auth.permissions
    if (!permissions) return false

    if (!ignoreSuperAdmin) requiredPermissions.push("SUPER_ADMIN")
    if (includesAny<SystemPermission>(permissions, requiredPermissions)) return true;
    return false;
}

export function hasPermission(permission: SystemPermission, ignoreSuperAdmin: boolean = false): boolean {
    return hasAnyPermission([permission], ignoreSuperAdmin)
}


/**
 * Returns permission level for current user
 */
export function getAuthPermissionLevel(): number {
    if (!auth.principal) return 0;

    const roles = mapRoles(auth.principal.roles);
    if (!roles) return 0;
    const permissions = rolesToPermissions(roles);
    const levels = permissions.map(p => p.level);
    const max = Math.max(...levels);

    return max;
}

/**
 * Returns the max permission level for a list of permissions
 */
export function getMaxPermissionLevel(list: SystemPermission[]): number {
    const permissions = list.map(v => getPermissionMeta(v));
    return Math.max(...permissions.map(v => v.level));
}

/**
 * Extracts list of permissions from a list of roles
 */
export function rolesToPermissions(roles: Role[]): PermissionMeta<SystemPermission>[] {
    const addedPermissions: SystemPermission[] = [];
    const allPermissions: PermissionMeta<SystemPermission>[] = [];

    roles.forEach(r => {
        const permissions = r.permissions;
        permissions.forEach(perm => {
            if (addedPermissions.includes(perm)) return;
            addedPermissions.push(perm);

            const meta = systemPermissionMeta[perm];
            allPermissions.push(meta);
        });
    });

    return allPermissions;
}

/**
 * Returns list of permissions for the current user
 */
export function getCurrentPermissions(): PermissionMeta<SystemPermission>[] | null {
    if (!auth.principal) return null;
    const roleIds = auth.principal.roles;
    const roles = roleIds.map(v => getRole(v)).filter(v => v != null);

    const permissions = rolesToPermissions(roles);
    return permissions;
}
