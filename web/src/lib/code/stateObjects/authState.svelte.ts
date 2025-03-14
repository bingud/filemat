

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
        console.log(`Auth state wiped.`)
    }
}


/**
 * Holds authentication state, like user principal, available roles, ...
 */
export const auth = new AuthState()