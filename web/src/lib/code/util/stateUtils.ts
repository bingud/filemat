import type { Role } from "../auth/types";
import { auth } from "../stateObjects/authState.svelte";
import type { ulid } from "../types";


export function getRole(id: ulid): Role | null {
    if (!auth.roleList) return null
    const role = auth.roleList.find((v) => v.roleId === id)
    return role ?? null
}