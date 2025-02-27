
class AuthState {
    principal: Principal | null = $state(null)
    roleList: Role[] | null = $state(null)
    authenticated: boolean | null = $state(false)

    constructor() {
        this.reset = this.reset.bind(this)
    }

    reset() {
        this.principal = null
        this.roleList = null
        this.authenticated = null
        console.log(`Auth state wiped.`)
    }
}

export const auth = new AuthState()