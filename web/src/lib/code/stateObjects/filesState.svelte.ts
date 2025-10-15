import { page } from "$app/state"
import type { FullFileMetadata } from "$lib/code/auth/types"
import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import { generateRandomNumber, isFolder, prependIfMissing, printStack, removeString, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { SingleChildBooleanTree } from "../../../routes/(app)/files/[...path]/_code/fileUtilities"


class FilesState {
    nonce = generateRandomNumber()

    /**
     * The current file path opened
     */
    path: string = $derived.by(() => {
        if (page.url.pathname.startsWith("/files")) {
            let path = page.params.path!
            return prependIfMissing(path, "/")
        } else {
            return "/"
        }
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
     * File sorting
     */
    sortingMode: "modified" | "created" | "name" | "size" = $state("modified")
    sortingDirection: "desc" | "asc" = $state("desc")
    
    /**
     * Clear state on file navigation
     */
    clearAllState() {
        this.data.clear()
        this.ui.clear()
    }

    clearOpenState() {
        this.data.clearOpenContent()
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
    metadataMap = $derived.by(() => {
        const entriesMap = new Map(
            filesState.data.entries?.map(e => [e.path, e]) || []
        )

        let obj: Record<string, FullFileMetadata | null> = Object.fromEntries(
            this.list.map(path => [
                path,
                path === filesState.path 
                    ? (filesState.data.currentMeta || null)
                    : (entriesMap.get(path) || null)
            ])
        )

        return obj
    })

    singlePath = $derived(this.list.length === 1 ? this.list[0] : null)
    singleMeta = $derived.by(() => {
        if (!this.metadataMap) return null

        // Only return if one entry is selected
        const metaList = valuesOf(this.metadataMap)
        if (metaList.length > 1) return null

        const value = metaList[0]
        return value
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

    newFilePopoverOpen = $state(false)

    fileContextMenuPopoverOpen = $state(false)

    toggleSidebar() {
        this.detailsOpen = !this.detailsOpen
    }

    clear() {
        if (!uiState.isDesktop) this.detailsOpen = false
    }
}

class FileDataStateClass {
    // Metadata of currently open file
    fileMeta = $state(null) as FullFileMetadata | null
    // Metadata of currently open parent folder
    folderMeta = $state(null) as FullFileMetadata | null

    currentMeta = $derived.by(() => {
        return this.fileMeta || this.folderMeta
    })

    // Raw content of currently open file
    content = $state(null) as Blob | null
    // Decoded content of currently open file
    decodedContent = $state(null) as any | null
    // Download URL of currently open file
    contentUrl = $derived.by(() => {
        if (!this.fileMeta) return null
        return `/api/v1/file/content?path=${filesState.path}`
    })
    // All entries in the current directory
    entries = $state(null) as FullFileMetadata[] | null
    // Sorted entries in the current directory
    sortedEntries = $derived.by(() => {
        if (!filesState.data || !filesState.data.entries) return null
        const files = filesState.data.entries.filter((v) => v.fileType === "FILE" || v.fileType === "FILE_LINK")
        const folders = filesState.data.entries.filter((v) => v.fileType === "FOLDER" || v.fileType === "FOLDER_LINK")
        
        // Sort entries
        const mode = filesState.sortingMode
        const direction = filesState.sortingDirection

        let sortedFiles: FullFileMetadata[] = []
        let sortedFolders: FullFileMetadata[] = []

        if (mode === "modified") {
            if (direction === "asc") {
                sortedFiles = sortArrayByNumber(files, f => f.modifiedDate)
                sortedFolders = sortArrayByNumber(folders, f => f.modifiedDate)
            } else {
                sortedFiles = sortArrayByNumberDesc(files, f => f.modifiedDate)
                sortedFolders = sortArrayByNumberDesc(folders, f => f.modifiedDate)
            }
        } else if (mode === "name") {
            sortedFiles = sortArrayAlphabetically(files, f => f.filename!, direction)
            sortedFolders = sortArrayAlphabetically(folders, f => f.filename!, direction)
        } else if (mode === "created") {
            if (direction === "asc") {
                sortedFiles = sortArrayByNumber(files, f => f.createdDate)
                sortedFolders = sortArrayByNumber(folders, f => f.createdDate)
            } else {
                sortedFiles = sortArrayByNumberDesc(files, f => f.createdDate)
                sortedFolders = sortArrayByNumberDesc(folders, f => f.createdDate)
            }
        } else if (mode === "size") {
            if (direction === "asc") {
                sortedFiles = sortArrayByNumber(files, f => f.size)
                sortedFolders = sortArrayByNumber(folders, f => f.size)
            } else {
                sortedFiles = sortArrayByNumberDesc(files, f => f.size)
                sortedFolders = sortArrayByNumberDesc(folders, f => f.size)
            }
        }

        return [...sortedFolders, ...sortedFiles]
    })

    clear() {
        this.fileMeta = null
        this.folderMeta = null
        this.content = null
        this.decodedContent = null
        this.entries = null
    }

    clearOpenContent() {
        this.fileMeta = null
        this.content = null
        this.decodedContent = null
    }
}


/**
 * # Util
 */


export let filesState: FilesState

/**
 * @returns class nonce
 */
export function createFilesState(): number {
    if (filesState) {
        console.log(`FilesState recreated`)
    } else {
        console.log(`FilesState created`)
    }
    filesState = new FilesState()
    return filesState.nonce
}

export function destroyFilesState(nonce: number) {
    if (filesState?.nonce === nonce) {
        filesState = undefined!
        console.log(`FilesState destroyed`)
    }
}