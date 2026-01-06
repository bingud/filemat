import { goto } from "$app/navigation"
import type { FullFileMetadata } from "$lib/code/auth/types"
import { getFileData, getFileListFromCustomEndpoint, getFileLastModifiedDate, startTusUpload, uploadWithTus, type FileData, navigateToFilePath } from "$lib/code/module/files"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { addSuffix, filenameFromPath, parentFromPath } from "$lib/code/util/codeUtil.svelte"
import { isDialogOpen, isUserInAnyInput } from "$lib/code/util/stateUtils"
import { toast } from "@jill64/svelte-toast"
import { textFileViewerState } from "./textFileViewerState.svelte"
import { sharedFilesPageState } from "../../../shared-files/state.svelte"


export function event_filesDropped(e: CustomEvent<{ files: FileList }>) {
    const files = Array.from(e.detail.files)
    
    files.forEach(file => {
        startTusUpload(file)
    })
}

// Scrolling position
export function saveScrollPosition() {
    if (!filesState.path || !filesState.scroll.container) return
    const pos = filesState.scroll.container.scrollTop
    filesState.scroll.pathPositions[filesState.path] = pos
}

export function recoverScrollPosition() {
    if (!filesState.path || !filesState.scroll.container) return
    const pos = filesState.scroll.pathPositions[filesState.path]
    if (!pos) return
    filesState.scroll.container.scrollTo({top: pos})
}


export async function loadPageData(
    filePath: string,
    options: {
        urlPath: string,
        silent?: boolean,
        isRefresh?: boolean,
        fileDataType: "object" | "array",
        parentFolderOnly?: boolean,
        loadParentFolder?: boolean,
        shareToken?: string,
        bodyParams?: Record<string, string>
    }
) {
    filesState.lastFilePathLoaded = filePath
    if (!options.silent && !options.parentFolderOnly) filesState.metaLoading = true

    // Get file metadata + folder entries
    const result = (
        options.fileDataType === "array"
        ? await getFileListFromCustomEndpoint({
                path: filePath,
                urlPath: options.urlPath,
                signal: filesState.abortController?.signal,
                silent: options.parentFolderOnly ?? false,
                bodyParams: options.bodyParams
            })
        : await getFileData(filePath, options.urlPath, filesState.abortController?.signal, { silent: options.parentFolderOnly, shareToken: options.shareToken })
    )
    filesState.metaLoading = false

    if (result.notFound) {
        if (filesState.path === "/") return
        if (options.isRefresh) {
            toast.plain("This file is not available anymore.")
        } else {
            toast.error("This file was not found.")
        }

        const pagePath = filesState.isShared ? filesState.meta.pagePath : "/files"
        await navigateToFilePath(parentFromPath(filesState.path), pagePath)
    }
    if (result.isUnsuccessful) return
    const dataResult = result.value

    if (filesState.lastFilePathLoaded !== filePath) return
    if (!result) return

    if (options.fileDataType === "object") {
        const data = dataResult as FileData
        const meta = data.meta
        const type = meta.fileType

        // If the metadata is a folder, set folder entries and folder metadata
        // If its a file, set file metadata
        if (type === "FOLDER") {
            if (!options.parentFolderOnly) filesState.data.fileMeta = null
            filesState.data.folderMeta = meta

            filesState.data.entries = data.entries || null
        } else if (type === "FOLDER_LINK") {
            if (appState.followSymlinks) {
                if (!options.parentFolderOnly) filesState.data.fileMeta = null
                filesState.data.folderMeta = meta

                data.entries?.forEach((entry) => {
                    const linkPath = `${addSuffix(filePath, "/")}${entry.filename!}`
                    entry.path = linkPath
                })
                filesState.data.entries = data.entries || null
            } else {
                filesState.data.fileMeta = meta
            }
        } else if (type === "FILE" || type === "FILE_LINK") {
            if (!options.parentFolderOnly) {
                filesState.data.fileMeta = meta
                if (!filesState.data.fileMeta.filename) {
                    filesState.data.fileMeta.filename = filenameFromPath(meta.path)
                }
            }

            // Load parent folder of file
            if (options.loadParentFolder && filesState.data.folderMeta == null && filePath !== "/") {
                loadPageData(parentFromPath(meta.path), {
                    urlPath: options.urlPath,
                    fileDataType: "object",
                    loadParentFolder: false,
                    parentFolderOnly: true,
                    shareToken: filesState.getShareToken()
                })
            }
        }

        // If no entry is selected and this is a folder, select the current folder
        if (filesState.selectedEntries.singlePath === null) {
            filesState.selectedEntries.list = [data.meta.path]
        }
    } else if (options.fileDataType === "array") {
        const data = dataResult as FullFileMetadata[]
        filesState.data.entries = data
    }
}


/**
 * Reload the current folder data.
 * If modification date of folder has changed, fetch new data
*/
export async function reloadCurrentFolder() {
    if (!filesState.path) return
    const meta = filesState.data.folderMeta

    const shareToken = filesState.getIsShared() ? filesState.meta.shareToken : undefined
    const bodyParams = filesState.meta.type === "allShared" ? { getAll: `${sharedFilesPageState.showAll}` } : undefined;

    if (meta) {
        const modifiedDate = meta.modifiedDate
        const actualModifiedDate = await getFileLastModifiedDate(meta.path, { shareToken: filesState.getShareToken() })

        // Folder modification date has not changed
        // Local folder is up to date
        if (modifiedDate === actualModifiedDate) return

        await loadPageData(meta.path, { urlPath: filesState.meta.fileEntriesUrlPath, parentFolderOnly: true, silent: true, fileDataType: "object", shareToken: shareToken, bodyParams })
    } else if (filesState.meta.isArrayOnly) {
        await loadPageData(filesState.path, { urlPath: filesState.meta.fileEntriesUrlPath, fileDataType: "array", shareToken: shareToken, bodyParams })
    }
}


export function handleKeyDown(event: KeyboardEvent) {
    if (isDialogOpen() || isUserInAnyInput()) return

    // Navigate to the parent folder
    if (event.key === "Backspace") {
        // bypass, if editing text file
        if (textFileViewerState.isFocused) return

        // bypass, if the current folder is already top-level
        const currentPath = filesState.path
        if (currentPath === "/") return

        const parentPath = currentPath.slice(0, currentPath.lastIndexOf("/"))
        goto(`/files${parentPath}`)
    }
}

// Functions for new item actions
export function handleUpload() {
    filesState.ui.newFilePopoverOpen = false
    uploadWithTus()
}

export function handleNewFile() {
    filesState.ui.newFilePopoverOpen = false
}

export function option_changeFileView() {
    filesState.ui.switchFileViewType();
}
