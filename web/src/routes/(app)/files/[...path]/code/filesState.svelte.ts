import { page } from "$app/state"
import type { FileMetadata } from "$lib/code/auth/types"
import type { ulid } from "$lib/code/types"
import { isBlank, sortArrayByNumberDesc } from "$lib/code/util/codeUtil.svelte"


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
    private FileDataClass = class {
        meta = $state(null) as FileMetadata | null
        content = $state(null) as Blob | null
        entries = $state(null) as FileMetadata[] | null
        sortedEntries = $derived.by(() => {
            if (!filesState.data || !filesState.data.entries) return null
            const files = filesState.data.entries.filter((v) => v.fileType === "FILE" || v.fileType === "FILE_LINK")
            const folders = filesState.data.entries.filter((v) => v.fileType === "FOLDER" || v.fileType === "FOLDER_LINK")
            
            const sortedFiles = sortArrayByNumberDesc(files, f => f.modifiedDate)
            const sortedFolders = sortArrayByNumberDesc(folders, f => f.modifiedDate)
    
            return [...sortedFolders, ...sortedFiles]
        })
    }
    data = new this.FileDataClass()

    abortController = $state(new AbortController())

    /**
     * Scrolling state
     */
    scroll = $state({
        container: null as HTMLElement | null,
        pathPositions: {} as Record<string, number>,
    })

    selectedEntry = $state(null) as ulid | null

    /**
     * Clear state on file navigation
     */
    clearState() {
        this.data.meta = null
        this.data.content = null
        this.data.entries = null
        this.selectedEntry = null
    }
}



export let filesState: FilesState

export function createFilesState() {
    filesState = new FilesState()
}

export function destroyFilesState() {
    filesState = undefined!
}