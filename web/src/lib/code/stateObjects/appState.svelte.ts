import { page } from "$app/state"
import { untrack } from "svelte"
import type { Role } from "../auth/types"
import type { ulid, ValuesOf } from "../types/types"
import { entriesOf, filePageUrl } from "../util/codeUtil.svelte"
import { auth } from "./authState.svelte"

export const filePagePaths = {
    "/files": "files",
    "/saved-files": "savedFiles",
    "/accessible-files": "accessibleFiles",
    "/share": "sharedFiles",
    "/shared-files": "allSharedFiles",
} as const

/**
 * Native path style of the server OS
 */
export type AppPlatform = { pathStyle: "posix" | "windows", separator: string, filenameRules: FilenameRules }

/**
 * Rules for a single file or folder name on the server OS (`FilenameRules` on the server)
 */
export type FilenameRules = {
    invalidChars: string,
    /**
     * Reject characters 0x00-0x1F
     */
    rejectControlChars: boolean,
    reservedNames: string[],
    /**
     * Reserved names also match with any extension and in any case (`con.txt` on Windows)
     */
    reservedNamesIgnoreExtension: boolean,
    rejectTrailingDotOrSpace: boolean,
    maxLength: number,
    lengthUnit: "utf8Bytes" | "utf16Units",
}

/**
 * Linux rules, used until the server state is loaded
 */
export const posixFilenameRules: FilenameRules = {
    invalidChars: `/\u0000`,
    rejectControlChars: false,
    reservedNames: [`.`, `..`],
    reservedNamesIgnoreExtension: false,
    rejectTrailingDotOrSpace: false,
    maxLength: 255,
    lengthUnit: `utf8Bytes`,
}

/**
 * Server file paths. They contain the OS username, so the server only sends them before setup or to logged-in users.
 */
export type AppPaths = {
    setupCodeFile: string,
    authCodeFile: string,
    defaultUploadFolder: string,
    defaultThumbnailCacheFolder: string,
}

const sitePaths = {
    ...filePagePaths,
    "/settings": "settings",
} as const

class AppState {
    settings = new SettingState()
    title = new TitleState()

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
     * Absolute prefix for content downloads, uploads, and thumbnails.
     */
    contentBaseUrl = $state(``)
    /**
     * Native path style and separator of the server OS
     */
    platform: AppPlatform = $state({ pathStyle: `posix`, separator: `/`, filenameRules: posixFilenameRules })
    /**
     * Server file paths, null until loaded or when not sent
     */
    paths: AppPaths | null = $state(null)
    
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
            const matches = current.startsWith(path)
            state[name] = matches
        })

        const homeFolderPath = auth.principal?.homeFolderPath
        state["home"] = homeFolderPath != null && current === filePageUrl(homeFolderPath)

        return state as Record<ValuesOf<typeof sitePaths> | "home", boolean>
    })
}

class SettingState {
    loadAllPreviews = $state(false)
    /** When enabled alongside loadAllPreviews, all image entries always render their
     *  real <img> elements instead of skeletonizing off-screen. Useful when there is
     *  no service worker to cache headless image fetches. */
    alwaysRenderPreviews = $state(false)
	defaultPagePath = $state<keyof typeof filePagePaths>("/files")
    clickToOpenFile = $state(false)
    previewSize = $state({ rows: 2, grid: 2 })
    /** Snap scrolling to fullscreen grid previews. */
    fullscreenScrollSnap = $state(true)
    homeFolderPath = $state(null)
}

class TitleState {
    segments: string[] = $state([])

    current = $derived.by(() => {
        const title = this.segments[this.segments.length - 1]
        if (!title) return "Filemat"
        return `${title} — Filemat`
    })

    register(segment: string) {
        untrack(() => {
            this.segments.push(segment)
            return () => {
                this.segments = this.segments.filter((s) => s !== segment)
            }
        })
    }
}

/**
 * Stores general app state and settings.
 */
export const appState = new AppState()


