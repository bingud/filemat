import type { Principal, Role } from "../auth/types"
import type { ulid } from "../types"

class AppState {
    /**
     * Indicates whether application has been set up.
     */
    isSetup: boolean | null = $state(null)
    /**
     * List of folder paths that are marked as sensitive.
     */
    sensitiveFolders: string[] | null = $state(null)
    
    /**
     * Indicates whether the first page the user entered is stil open 
     * or whether the user navigated
     */
    isInitialPageOpen = $state(true)

    /**
     * List of all roles in the system
     */
    roleList: Role[] | null = $state(null)
    roleListObject: { [key: ulid]: Role } | null = $derived.by(() =>{
        if (this.roleList) {
            let obj: typeof this.roleListObject = {}
            this.roleList.forEach(v => {
                obj[v.roleId] = v
            })
            return obj
        } else { return null }
    })

    /**
     * List of default system role IDs
     */
    systemRoleIds: { user: ulid, admin: ulid } | null = $state(null)
}

/**
 * Stores general app state and settings.
 */
export const appState = new AppState()


