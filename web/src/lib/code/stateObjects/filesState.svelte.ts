import { page } from "$app/state"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import type { ulid } from "$lib/code/types/types"
import { prependIfMissing, removeString, sortArrayByNumberDesc, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { SingleChildBooleanTree } from "../../../routes/(app)/files/[...path]/content/code/files"
import { UploadState } from "./subState/uploadState.svelte"


class FilesState {
    /**
     * The current file path opened
     */
    path: string = $derived.by(() => {
        let path = page.params.path
        return prependIfMissing(path, "/")
    })

    /**
     * File path segments of the current path
     */
    segments = $derived(this.path.split("/").filter(v => v !== ""))
    
    /**
     * Loading indicators
     */
    metaLoading = $state(false)
    contentLoading = $state(false)

    /**
     * File data
     */
    data = new FileDataStateClass()

    ui = new FileUiStateClasss()

    abortController = new AbortController()
    abort() {
        try {
            this.abortController.abort()
        } catch (e) {}
        this.abortController = new AbortController()
    }

    /**
     * Scrolling state
     */
    scroll = $state({
        container: null as HTMLElement | null,
        pathPositions: {} as Record<string, number>,
    })

    /**
     * Selected entry state
     */
    selectedEntries = new SelectedEntryStateClass()

    lastFilePathLoaded = $state(null) as string | null
    
    /**
     * Clear state on file navigation
     */
    clearState() {
        this.data.clear()
        this.ui.clear()
    }

    unselect() {
        if (this.selectedEntries) {
            this.selectedEntries.list = []
        }
    }
}


/**
 * # Classes
 */


class SelectedEntryStateClass {
    selectedPositions = new SingleChildBooleanTree()

    list = $state([]) as string[]
    // path = $state(null) as ulid | null
    meta = $derived.by(() => {
        if (!filesState.data.entries) return null
        let obj: Record<string, FullFileMetadata | null> = {}
        this.list.forEach((path) => {
            if (filesState.path === path) {
                const meta = filesState.data.meta
                obj[path] = meta || null
            } else {
                const meta = filesState.data.entries!.find(v => v.path === path)
                obj[path] = meta || null
            }
        })
        return obj
    })

    single = $derived(this.list.length === 1 ? this.list[0] : null)
    singleMeta = $derived.by(() => {
        if (!this.meta) return null

        // Only return if one entry is selected
        const values = valuesOf(this.meta)
        if (values.length > 1) return null

        return values[0]
    })

    hasMultiple = $derived(this.list.length > 1)
    hasSelected = $derived(this.list.length > 0)
    count = $derived(this.list.length)

    setSelected(path: string, preventSave: boolean = false) {
        this.list = [path]
        if (!preventSave) {
            this.selectedPositions.set(path, true)
        }
    }

    addSelected(path: string) {
        if (this.list.includes(path)) return
        this.list.push(path)
    }

    unselect(path: string) {
        removeString(this.list, path)
    }
    unselectAll(paths: string[]) {
        paths.forEach(deletedPath => {
            removeString(this.list, deletedPath)
        })
    }
    reset() {
        this.list = []
    }
}

class FileUiStateClasss {
    /**
     * Is details opened
     */
    detailsOpen = $state(uiState.isDesktop)

    toggleSidebar() {
        this.detailsOpen = !this.detailsOpen
    }

    clear() {
        if (!uiState.isDesktop) this.detailsOpen = false
    }
}

class FileDataStateClass {
    // Metadata of currently open file
    meta = $state(null) as FullFileMetadata | null
    // Raw content of currently open file
    content = $state(null) as Blob | null
    // Decoded content of currently open file
    decodedContent = $state(null) as any | null
    // Download URL of currently open file
    contentUrl = $derived(`/api/v1/file/content?path=${filesState.path}`)
    // All entries in the current directory
    entries = $state(null) as FullFileMetadata[] | null
    // Sorted entries in the current directory
    sortedEntries = $derived.by(() => {
        if (!filesState.data || !filesState.data.entries) return null
        const files = filesState.data.entries.filter((v) => v.fileType === "FILE" || v.fileType === "FILE_LINK")
        const folders = filesState.data.entries.filter((v) => v.fileType === "FOLDER" || v.fileType === "FOLDER_LINK")
        
        const sortedFiles = sortArrayByNumberDesc(files, f => f.modifiedDate)
        const sortedFolders = sortArrayByNumberDesc(folders, f => f.modifiedDate)

        return [...sortedFolders, ...sortedFiles]
    })

    clear() {
        this.meta = null
        this.content = null
        this.decodedContent = null
        this.entries = null
    }
}


/**
 * # Util
 */


export let filesState: FilesState

export function createFilesState() {
    filesState = new FilesState()
}

export function destroyFilesState() {
    filesState = undefined!
}