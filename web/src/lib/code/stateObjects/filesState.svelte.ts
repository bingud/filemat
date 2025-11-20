import { page } from "$app/state"
import type { FullFileMetadata } from "$lib/code/auth/types"
import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import { generateRandomNumber, isFolder, keysOf, prependIfMissing, printStack, removeString, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { ImageLoadQueue } from "../../../routes/(app)/(files)/files/[...path]/_code/fileBrowserUtil"
import { SingleChildBooleanTree } from "../../../routes/(app)/(files)/files/[...path]/_code/fileUtilities"
import { config } from "../config/values"
import { fileSortingDirections, fileSortingModes, type FileSortingMode, type SortingDirection } from "../types/fileTypes"
import { getContentUrl } from "../util/stateUtils"
import { fileViewType_saveInLocalstorage } from "../util/uiUtil"
import { appState } from "./appState.svelte"


export type StateMetadata = { isFiles: true, isSharedFiles: false, isAccessibleFiles: false, fileEntriesUrlPath: string, pagePath: string, pageTitle: string }
                          | { isFiles: false, isSharedFiles: true, isAccessibleFiles: false, shareId: string, fileEntriesUrlPath: string, pagePath: string, pageTitle: string }
                          | { isFiles: false, isSharedFiles: false, isAccessibleFiles: true, fileEntriesUrlPath: string, pagePath: string, pageTitle: string }


class FilesState {
    meta: StateMetadata = $state(undefined!)

    /**
     * The current file path opened
     */
    path: string = $derived.by(() => {
        if (appState.currentPath.files || appState.currentPath.sharedFiles) {
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

    isFolderOpen = $derived.by(() => { return isFolder(this.data.currentMeta) })
    isFileListOpen = $derived.by(() => {
        const currentMeta = this.data.currentMeta
        const hasEntries = !!this.data.entries
        
        if (this.isFolderOpen || currentMeta == null && hasEntries) return true
        return false
    })

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
    sortingMode: FileSortingMode = $state("modified")
    sortingDirection: SortingDirection = $state("desc")
    setFileSortingMode(mode: FileSortingMode) {
        this.sortingMode = mode
        localStorage.setItem("fm-sorting-mode", mode)
    }
    setFileSortingDirection(dir: SortingDirection) {
        this.sortingDirection = dir
        localStorage.setItem("fm-sorting-direction", dir)
    }

    constructor() {
        // Load file sorting options
        const mode = localStorage.getItem("fm-sorting-mode")
        const direction = localStorage.getItem("fm-sorting-direction")
        if (mode && keysOf(fileSortingModes).includes(mode as any)) this.sortingMode = mode as FileSortingMode
        if (direction && fileSortingDirections.includes(direction as any)) this.sortingDirection = direction as SortingDirection
    }
    
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
    isCurrentPathSelected = $derived(filesState.path === this.singlePath)
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

    /**
     * Is popover for file creation / upload open
     */
    newFilePopoverOpen = $state(false)

    /**
     * Is a file context menu popover open
     */
    fileContextMenuPopoverOpen = $state(false)
    
    /**
     * File view type
     */
    fileViewType = $state("rows")  as "rows" | "tiles"

    /**
     * Is file sorting menu popover open
     */
    fileSortingMenuPopoverOpen = $state(false)

    filePreviewLoader = $state(new ImageLoadQueue())

    toggleSidebar() {
        this.detailsOpen = !this.detailsOpen
    }
    switchFileViewType(): typeof this.fileViewType {
        const newType = this.fileViewType === "rows" 
                ? "tiles" 
                : "rows"

        this.fileViewType = newType
        fileViewType_saveInLocalstorage(newType)
        return newType
    }

    clear() {
        if (!uiState.isDesktop) this.detailsOpen = false
        this.fileContextMenuPopoverOpen = false
        this.newFilePopoverOpen = false
        this.fileSortingMenuPopoverOpen = false
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
        return getContentUrl(this.fileMeta.path)
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
export function createFilesState(meta: StateMetadata): number | null {
    if (filesState) {
        console.log(`FilesState recreated`)
    } else {
        console.log(`FilesState created`)
    }
    
    filesState = new FilesState()
    appState.filesStateNonce = generateRandomNumber()
    filesState.meta = meta

    return appState.actualFilesStanceNonce
}

export function destroyFilesState(nonce: number | null) {
    if (appState.actualFilesStanceNonce === nonce) {
        filesState = undefined!
        appState.filesStateNonce = null
        console.log(`FilesState destroyed`)
    }
}