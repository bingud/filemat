import type { Permission } from "$lib/code/auth/types";
import { getCurrentPermissions, getMaxPermissionLevel, rolesToPermissions } from "$lib/code/data/permissions";
import { auth } from "$lib/code/stateObjects/authState.svelte";
import type { SettingSectionId, uiState } from "$lib/code/stateObjects/uiState.svelte";
import { includesList, valuesOf } from "$lib/code/util/codeUtil.svelte";
import { getRole } from "$lib/code/util/stateUtils";


export type SettingsSection = { name: SettingSectionId, permissions: Permission[], admin: boolean }
export const settingSections = {
    all() {
        return valuesOf(settingSections.sections)
    },
    allUser() {
        return valuesOf(settingSections.sections).filter(v => !v.admin)
    },
    allAdmin() {
        return valuesOf(settingSections.sections).filter(v => v.admin)
    },
    hasPermission(sectionName: SettingSectionId): boolean {
        if (!sectionName) return false
        const section = settingSections.sections[sectionName.toLowerCase()]
        if (!section) return false
        if (!section.admin) return true

        const permissionMetas = getCurrentPermissions()
        if (!permissionMetas) return false
        const permissions = permissionMetas.map(v=>v.id)

        const hasPermission = includesList(permissions, section.permissions) || permissions.includes("SUPER_ADMIN")
        
        return hasPermission
    },
    sections: {
        "preferences": { name: "preferences", permissions: [], admin: false },
        "users": { name: "users", permissions: ["MANAGE_USERS"], admin: true },
        "roles": { name: "roles", permissions: ["EDIT_ROLES"], admin: true },
    } as Record<string, SettingsSection>
}