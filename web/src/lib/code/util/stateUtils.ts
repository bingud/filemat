import type { Role } from "../auth/types";
import { appState } from "../stateObjects/appState.svelte";
import { auth } from "../stateObjects/authState.svelte";
import type { ulid } from "../types/types";


export function getRole(id: ulid): Role | null {
    if (!appState.roleList) return null
    const role = appState.roleList.find((v) => v.roleId === id)
    return role ?? null
}

export function mapRoles(roleIds: ulid[]): Role[] | null {
    if (!appState.roleListObject) return null
    const list: (Role | null)[] = roleIds.map(v => appState.roleListObject![v])
    if (list.includes(null)) return null
    return list as Role[]
}