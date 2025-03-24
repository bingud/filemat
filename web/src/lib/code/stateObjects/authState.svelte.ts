import type { Principal, Role } from "../auth/types"
import { getAuthPermissionLevel } from "../data/permissions"
import type { ulid } from "../types"
import { appState } from "./appState.svelte"


class AuthState {
    /**
     * User authentication object
     */
    principal: Principal | null = $state(null)

    /**
     * Indicates whether user is logged in.
     */
    authenticated: boolean | null = $state(false)

    /**
     * Indicates whether user has the admin role
     */
    isAdmin = $derived.by(() => {
        if (!this.principal || !appState.systemRoleIds) return null
        return this.principal.roles.includes(appState.systemRoleIds.admin)
    })

    permissionLevel = $derived.by(() => {
        if (!this.principal || !this.principal.roles) return 0
        return getAuthPermissionLevel()
    })

    constructor() {
        this.reset = this.reset.bind(this)
    }

    /**
     * Resets the authentication state.
     */
    reset() {
        this.principal = null
        this.authenticated = null
        console.log(`Auth state wiped.`)
    }
}


/**
 * Holds authentication state, like user principal, available roles, ...
 */
export const auth = new AuthState()