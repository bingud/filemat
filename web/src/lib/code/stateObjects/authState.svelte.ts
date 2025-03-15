import type { Principal, Role } from "../auth/types"
import type { ulid } from "../types"


class AuthState {
    /**
     * User authentication object
     */
    principal: Principal | null = $state(null)
    /**
     * List of all roles in the system
     */
    roleList: Role[] | null = $state(null)
    /**
     * Indicates whether user is logged in.
     */
    authenticated: boolean | null = $state(false)
    /**
     * List of default system role IDs
     */
    systemRoleIds: { user: ulid, admin: ulid } | null = $state(null)
    /**
     * Indicates whether user has the admin role
     */
    isAdmin = $derived.by(() => {
        if (!this.principal || !this.systemRoleIds) return null
        return this.principal.roles.includes(this.systemRoleIds.admin)
    })

    constructor() {
        this.reset = this.reset.bind(this)
    }

    /**
     * Resets the authentication state.
     */
    reset() {
        this.principal = null
        this.roleList = null
        this.authenticated = null
        this.systemRoleIds = null
        console.log(`Auth state wiped.`)
    }
}


/**
 * Holds authentication state, like user principal, available roles, ...
 */
export const auth = new AuthState()