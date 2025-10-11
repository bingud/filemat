import { goto } from "$app/navigation"
import type { FullFileMetadata } from "$lib/code/auth/types"
import { getFileData, getFileDataWithDifferentPath, getFileLastModifiedDate, startTusUpload, uploadWithTus, type FileData } from "$lib/code/module/files"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { addSuffix, isFolder, parentFromPath } from "$lib/code/util/codeUtil.svelte"
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


export async function loadPageData(filePath: string, options: { silent?: boolean, isRefresh?: boolean, overrideDataUrlPath?: string, dataType: "object" | "array" }) {
    filesState.lastFilePathLoaded = filePath
    if (!options.silent) filesState.metaLoading = true

    const result = (options.overrideDataUrlPath
        ? await getFileDataWithDifferentPath(filePath, options.overrideDataUrlPath, filesState.abortController?.signal)
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

    if (options.dataType === "object") {
        const data = dataResult as FileData
        filesState.data.meta = data.meta!

        if (data.meta.fileType === "FOLDER") {
            filesState.data.entries = data.entries || null
        } else if (data.meta.fileType === "FOLDER_LINK") {
            if (appState.followSymlinks) {
                data.entries?.forEach((entry) => {
                    const linkPath = `${addSuffix(filePath, "/")}${entry.filename!}`
                    entry.path = linkPath
                })
                filesState.data.entries = data.entries || null
            }
        }

        // If no entry is selected and this is a folder, select the current folder
        if (filesState.selectedEntries.single === null && data.meta.fileType === "FOLDER") {
            filesState.selectedEntries.list = [data.meta.path]
        }
    } else {
        const data = dataResult as FullFileMetadata[]
        filesState.data.entries = data
    }
}


/**
 * Reload the current folder data.
 * If modification date of folder has changed, fetch new data
*/
export async function reloadPageData() {
    if (!filesState.path) return
    const meta = filesState.data.meta
    if (!meta || !isFolder(meta)) return

    const modifiedDate = meta.modifiedDate
    const actualModifiedDate = await getFileLastModifiedDate(filesState.path)

    // Folder modification date has not changed
    // Local folder is up to date
    if (modifiedDate === actualModifiedDate) return

    await loadPageData(filesState.path, { silent: true, dataType: "object" })
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