import { page } from "$app/state"
import type { FileMetadata } from "$lib/code/auth/types"
import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import type { ulid } from "$lib/code/types"
import { isBlank, sortArrayByNumberDesc } from "$lib/code/util/codeUtil.svelte"
import { SingleChildBooleanTree } from "./files"


class FilesState {
    /**
     * The current file path opened
     */
    path: string = $derived.by(() => {
        let path = page.params.path
        if (isBlank(path)) return "/"
        return path
    })

    /**
     * File path segments of the current path
     */
    segments = $derived(this.path.split("/"))
    
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
    selectedEntry = new SelectedEntryStateClass()

    lastFilePathLoaded = $state(null) as string | null
    

    /**
     * Clear state on file navigation
     */
    clearState() {
        this.data.clear()
        this.ui.clear()
    }

    unselect() {
        if (this.selectedEntry) {
            this.selectedEntry.path = null
        }
    }
}


/**
 * # Classes
 */


class SelectedEntryStateClass {
    selectedPositions = new SingleChildBooleanTree()

    path = $state(null) as ulid | null
    meta = $derived.by(() => {
        if (!filesState.data.entries || !this.path) return null
        return filesState.data.entries.find(v => v.filename === this.path)
    })
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
    meta = $state(null) as FileMetadata | null
    content = $state(null) as Blob | null
    decodedContent = $state(null) as any | null
    contentUrl = $derived(`/api/v1/file/content?path=${filesState.path}`)
    entries = $state(null) as FileMetadata[] | null
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