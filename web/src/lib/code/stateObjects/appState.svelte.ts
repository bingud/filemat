import { page } from "$app/state"
import type { Principal, Role } from "../auth/types"
import type { ulid, ValuesOf } from "../types/types"
import { entriesOf, valuesOf } from "../util/codeUtil.svelte"

export const filePagePaths = {
    "/files": "files",
    "/saved-files": "savedFiles",
    "/accessible-files": "accessibleFiles",
    "/share": "sharedFiles",
    "/shared-files": "allSharedFiles",
} as const

const sitePaths = {
    ...filePagePaths,
    "/settings": "settings",
} as const

class AppState {
    settings = new SettingState()

    /**
     * Indicates whether application has been set up.
     */
    isSetup: boolean | null = $state(null)
    /**
     * List of folder paths that are marked as sensitive.
     */
    sensitiveFolders: string[] | null = $state(null)
    /**
     * Whether the system follows symlinks
     */
    followSymlinks = $state(false)
    
    /**
     * Indicates whether the first page the user entered is stil open 
     * or whether the user navigated
     */
    isInitialPageOpen = $state(true)

    /**
     * The last time the application state was refreshed
     */
    lastFullStateRefresh: number | null = $state(null)

    /**
     * The hash code for the entire application state (only applies to everything combined)
     */
    stateHashCode: number | null = $state(null)

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

    // This is needed to fix some stupid Svelte race condition
    // Have to store nonce outside of $state
    actualFilesStanceNonce: number | null = null
    #filesStateNonce: number | null = $state(0)
    set filesStateNonce(n: number | null) { 
        this.#filesStateNonce = n 
        this.actualFilesStanceNonce = n
    }
    get filesStateNonce() { return this.#filesStateNonce }

    currentPath = $derived.by(() => {
        const current = page.url.pathname
        const state: any = {}
        
        entriesOf(sitePaths).forEach(([path, name]) => {
            state[name] = (current.startsWith(path))
        })
        return state as Record<ValuesOf<typeof sitePaths>, boolean>
    })
}

class SettingState {
    loadAllPreviews = $state(false)
	defaultPagePath = $state<keyof typeof filePagePaths>("/files")
    clickToOpenFile = $state(false)
}

/**
 * Stores general app state and settings.
 */
export const appState = new AppState()


