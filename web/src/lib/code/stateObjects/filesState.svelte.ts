import { page } from "$app/state"
import type { FullFileMetadata } from "$lib/code/auth/types"
import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import { filenameFromPath, generateRandomNumber, isFolder, keysOf, prependIfMissing, printStack, removeString, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc, sortFileMetadata, valuesOf } from "$lib/code/util/codeUtil.svelte"
import { SvelteSet } from "svelte/reactivity"
import { ImageLoadQueue } from "../../../routes/(app)/(files)/files/[...path]/_code/fileBrowserUtil"
import { SingleChildBooleanTree } from "../../../routes/(app)/(files)/files/[...path]/_code/fileUtilities"
import { getFileCategoryFromFilename, type FileCategory } from "../data/files"
import { fileSortingDirections, fileSortingModes, type FileSortingMode, type SortingDirection } from "../types/fileTypes"
import { getContentUrl } from "../util/stateUtils"
import { fileViewType_saveInLocalstorage } from "../util/uiUtil"
import { appState } from "./appState.svelte"
import { auth } from "./authState.svelte"

type StateMetadataProps = { fileEntriesUrlPath: string, pagePath: string, pageTitle: string, isArrayOnly: boolean }
export type StateMetadata = { type: "files",                                                                            } & StateMetadataProps
                          | { type: "shared",      shareId: string, shareToken: string, shareTopLevelFilename: string,  } & StateMetadataProps
                          | { type: "accessible",                                                                       } & StateMetadataProps
                          | { type: "allShared",                                                                        } & StateMetadataProps
                          | { type: "saved",                                                                            } & StateMetadataProps


class FilesState {
    meta: StateMetadata = $state(undefined!)
    getShareToken(): string | undefined { return this.getIsShared() ? this.meta.shareToken : undefined }

    /**
     * The current file path opened
     */
    path: string = $derived.by(() => {
        if (appState.currentPath.files || appState.currentPath.sharedFiles) {
            let path = page.params.path
            if (!path) return "/"
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
    isSearchOpen = $derived.by(() => {
        return !!filesState.search.sortedEntries
    })
    isShared = $derived(this.meta?.type === "shared")
    getIsShared = (): this is { meta: { type: "shared"; shareToken: string } } => this.isShared

    /**
     * File data
     */
    data = new FileDataStateClass()
    ui = new FileUiStateClasss()
    search = new FileSearchStateClass()
    currentFile = new FileStateClass()

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
    mixFilesAndFolders = $state(false)
    setFileSortingMode(mode: FileSortingMode) {
        this.sortingMode = mode
        localStorage.setItem("fm-sorting-mode", mode)
    }
    setFileSortingDirection(dir: SortingDirection) {
        this.sortingDirection = dir
        localStorage.setItem("fm-sorting-direction", dir)
    }
    setMixFilesAndFolders(mode: boolean) {
        this.mixFilesAndFolders = mode
        localStorage.setItem("fm-mix-files-and-folders", mode.toString())
    }

    constructor() {
        // Load file sorting options
        const mode = localStorage.getItem("fm-sorting-mode")
        const direction = localStorage.getItem("fm-sorting-direction")
        const mix = localStorage.getItem("fm-mix-files-and-folders")

        if (mode && keysOf(fileSortingModes).includes(mode as any)) this.sortingMode = mode as FileSortingMode
        if (direction && fileSortingDirections.includes(direction as any)) this.sortingDirection = direction as SortingDirection
        if (mix) this.mixFilesAndFolders = (mix === "true")
    }
    
    /**
     * Clear state on file navigation
     */
    clearAllState() {
        this.data.clear()
        this.ui.clear()
        this.search.clear()
    }

    clearOpenState() {
        this.data.clearOpenContent()
        this.ui.clear()
    }

    unselect() {
        if (this.selectedEntries) this.selectedEntries.reset()
    }
}


/**
 * # Classes
 */


class SelectedEntryStateClass {
    selectedPositions = new SingleChildBooleanTree()

    list = $state([]) as string[]
    searchList = $state([]) as string[]

    set = new SvelteSet()
    searchSet = new SvelteSet()

    currentList = $derived(filesState.isSearchOpen ? this.searchList : this.list)
    currentSet = $derived(filesState.isSearchOpen ? this.searchSet : this.set)

    metadataMap = $derived.by(() => {
        const entriesMap = new Map(
            filesState.data.entries?.map(e => [e.path, e]) || []
        )

        return Object.fromEntries(
            this.list.map(path => [
                path,
                path === filesState.path 
                    ? (filesState.data.currentMeta || null)
                    : (entriesMap.get(path) || null)
            ])
        )
    })
    searchMetadataMap = $derived.by(() => {
        const entriesMap = new Map(
            filesState.search.entries?.map(e => [e.path, e]) || []
        )
        return Object.fromEntries(
            this.searchList.map(path => [
                path,
                entriesMap.get(path) || null
            ])
        )
    })

    singlePath = $derived.by(() => {
        if (filesState.isSearchOpen) {
            if (this.searchList.length === 1) {
                return this.searchList[0]
            }
            return null
        }
        return this.list.length === 1 ? this.list[0] : null
    })
    singleMeta = $derived.by(() => {
        if (!this.metadataMap || (filesState.isSearchOpen && !this.searchMetadataMap)) return null

        // Only return if one entry is selected
        const metaList = filesState.isSearchOpen ? valuesOf(this.searchMetadataMap) : valuesOf(this.metadataMap)
        if (metaList.length > 1) return null

        const value = metaList[0]
        return value
    })

    hasMultiple = $derived(filesState.isSearchOpen ? (this.searchList.length > 1) : (this.list.length > 1))
    hasSelected = $derived(filesState.isSearchOpen ? (this.searchList.length > 0) : (this.list.length > 0))
    isCurrentPathSelected = $derived(filesState.path === this.singlePath)
    count = $derived(filesState.isSearchOpen ? this.searchList.length : this.list.length)

    setSelected(path: string | string[], preventSave: boolean = false) {
        const paths = Array.isArray(path) ? path : [path]
        
        if (filesState.isSearchOpen) {
            this.searchList = paths
            this.searchSet.clear()
            paths.forEach(p => this.searchSet.add(p))
        } else {
            this.list = paths
            this.set.clear()
            paths.forEach(p => this.set.add(p))
            if (!preventSave) {
                paths.forEach(p => this.selectedPositions.set(p, true))
            }
        }
    }

    addSelected(path: string) {
        if (filesState.isSearchOpen) {
            this.searchSet.add(path)
            
            if (this.searchList.includes(path)) return
            this.searchList.push(path)
        } else {
            this.set.add(path)

            if (this.list.includes(path)) return
            this.list.push(path)
        }
    }

    unselect(path: string) {
        if (filesState.isSearchOpen) {
            removeString(this.searchList, path)
            this.searchSet.delete(path)
        } else {
            this.set.delete(path)
            removeString(this.list, path)
        }
    }
    unselectAll(paths: string[] | undefined = undefined) {
        if (paths) {
            paths.forEach(deletedPath => {
                this.unselect(deletedPath)
                this.set.delete(deletedPath)
            })
        } else {
            this.setSelected([])
        }
    }
    reset() {
        this.list = []
        this.searchList = []
        this.set.clear()
        this.searchSet.clear()
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

    contentFilePath = $state(null) as string | null
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
        if (filesState.meta.type === "allShared") return this.entries

        return sortFileMetadata(filesState.data.entries, filesState.sortingMode, filesState.sortingDirection, filesState.mixFilesAndFolders)
    })

    isFileSymlink = $derived.by(() => {
        if (!this.fileMeta) return false
        return this.fileMeta.fileType.includes("LINK") && !appState.followSymlinks
    })

    clear() {
        this.fileMeta = null
        this.folderMeta = null
        this.content = null
        this.decodedContent = null
        this.entries = null
        this.contentFilePath = null

        filesState.currentFile.clear()
    }

    clearOpenContent() {
        this.fileMeta = null
        this.content = null
        this.decodedContent = null
        this.contentFilePath = null

        filesState.currentFile.clear()
    }
}

class FileSearchStateClass {
    text = $state(null) as string | null
    #_abortFunction: (() => any) | null = null

    entries = $state(null) as FullFileMetadata[] | null
    sortedEntries = $derived.by(() => {
        if (!this.entries) return null
        return sortFileMetadata(this.entries, filesState.sortingMode, filesState.sortingDirection, filesState.mixFilesAndFolders)
    })
    isLoading = $state(false)

    searchPath = $state(null) as string | null

    get abortFunction() {
        return this.#_abortFunction
    }
    set abortFunction(value: (() => any) | null) {
        if (this.#_abortFunction) this.#_abortFunction()
        this.#_abortFunction = value
    }

    clear() {
        if (this.#_abortFunction) this.#_abortFunction()
        this.#_abortFunction = null

        this.text = null
        this.entries = null
        this.searchPath = null
        filesState.selectedEntries.searchList = []
    }
}

class FileStateClass { 
    originalFileCategory: FileCategory | null = $derived.by(() => {
        if (!filesState.data.fileMeta) return null
        if (filesState.data.isFileSymlink) return "text" as FileCategory

        if (filesState.getIsShared() && filesState.path === "/") {
            return getFileCategoryFromFilename(filesState.meta.shareTopLevelFilename)
        }

        const filename = filesState.data.fileMeta.filename || filenameFromPath(filesState.data.fileMeta.path)
        return getFileCategoryFromFilename(filename)
    })

    displayedFileCategory = $derived(this.originalFileCategory) as FileCategory | null

    isEditable = $derived(
        this.displayedFileCategory === "text" 
        && !filesState.isShared
        && auth.authenticated 
        && (filesState.data.fileMeta && filesState.data.fileMeta.permissions!.includes("WRITE")) 
        && !filesState.data.isFileSymlink
    )

    clear() {
        this.displayedFileCategory = null
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