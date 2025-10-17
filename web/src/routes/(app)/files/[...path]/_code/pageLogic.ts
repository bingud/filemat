import { goto } from "$app/navigation"
import type { FullFileMetadata } from "$lib/code/auth/types"
import { getFileData, getFileListFromCustomEndpoint, getFileLastModifiedDate, startTusUpload, uploadWithTus, type FileData } from "$lib/code/module/files"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { addSuffix, filenameFromPath, isFolder, parentFromPath, Result } from "$lib/code/util/codeUtil.svelte"
import { isDialogOpen } from "$lib/code/util/stateUtils"
import { toast } from "@jill64/svelte-toast"



export function event_filesDropped(e: CustomEvent<{ files: FileList }>) {
    console.log(`file droippe`, e.detail.files)
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
        silent?: boolean,
        isRefresh?: boolean,
        overrideDataUrlPath?: string,
        fileDataType: "object" | "array",
        parentFolderOnly?: boolean,
    }
) {
    filesState.lastFilePathLoaded = filePath
    if (!options.silent) filesState.metaLoading = true

    // Get file metadata + folder entries
    const result = (
        options.overrideDataUrlPath
        ? await getFileListFromCustomEndpoint(filePath, options.overrideDataUrlPath, filesState.abortController?.signal)
        : await getFileData(filePath, filesState.abortController?.signal, {})
    )

    if (result.notFound) {
        if (filesState.path === "/") return
        if (options.isRefresh) {
            toast.plain("This folder is not available anymore.")
        } else {
            toast.error("This folder was not found.")
        }

        await goto(`/files/${parentFromPath(filesState.path)}`)
    }
    if (result.isUnsuccessful) return
    const dataResult = result.value

    if (filesState.lastFilePathLoaded !== filePath) return
    
    filesState.metaLoading = false
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
                filesState.data.fileMeta.filename = filenameFromPath(meta.path)
            }

        }

        // If no entry is selected and this is a folder, select the current folder
        if (filesState.selectedEntries.singlePath === null && type === "FOLDER") {
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
    if (!meta || !isFolder(meta)) return

    const modifiedDate = meta.modifiedDate
    const actualModifiedDate = await getFileLastModifiedDate(meta.path)

    // Folder modification date has not changed
    // Local folder is up to date
    if (modifiedDate === actualModifiedDate) return

    await loadPageData(meta.path, { parentFolderOnly: true, silent: true, fileDataType: "object" })
}


export function handleKeyDown(event: KeyboardEvent) {
    if (isDialogOpen()) return

    // Navigate to the parent folder
    if (event.key === "Backspace") {
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
