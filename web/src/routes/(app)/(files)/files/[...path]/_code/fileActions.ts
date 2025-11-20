import { filePermissionMeta } from "$lib/code/data/permissions";
import { moveMultipleFiles, moveFile, deleteFiles, downloadFilesAsZip } from "$lib/code/module/files";
import { filesState } from "$lib/code/stateObjects/filesState.svelte";
import { confirmDialogState, folderSelectorState } from "$lib/code/stateObjects/subState/utilStates.svelte";
import { filenameFromPath, formData, handleErr, handleException, isPathDirectChild, keysOf, parseJson, resolvePath, safeFetch, unixNowMillis, valuesOf } from "$lib/code/util/codeUtil.svelte";
import { toast } from "@jill64/svelte-toast";
import { textFileViewerState } from "./textFileViewerState.svelte";


export async function option_moveSelectedFiles() {
    if (!filesState.selectedEntries.hasSelected || !filesState.data.folderMeta) return

    const newParentPath = await folderSelectorState.show!({
        title: "Choose the target folder.",
        initialSelection: filesState.path
    })
    if (!newParentPath) return

    if (filesState.selectedEntries.hasMultiple) {
        moveMultipleFiles(newParentPath, filesState.selectedEntries.list)
    } else {
        const selected = filesState.selectedEntries.singlePath!
        const filename = filenameFromPath(selected)
        moveFile(selected, resolvePath(newParentPath, filename))
    }
}


export function option_deleteSelectedFiles() {
    const selected = filesState.selectedEntries.list;
    if (!selected.length) return;

    confirmDialogState.show({
        title: "Delete File",
        message: `Are you sure you want to delete ${filesState.selectedEntries.count} selected file${filesState.selectedEntries.count > 1 ? 's' : ''}? This cannot be undone.`,
        confirmText: "Delete",
        cancelText: "Cancel"
    })?.then((confirmed: boolean) => {
        if (!confirmed) return;
        if (!filesState.selectedEntries.metadataMap) return;

        const list = valuesOf(filesState.selectedEntries.metadataMap).filter(v => !!v);
        deleteFiles(list);
    });
}


export function option_downloadSelectedFiles() {
    const selected = filesState.selectedEntries.list
    if (!selected || !selected.length) return
    downloadFilesAsZip(selected)
}

export async function handleNewFolder() {
    filesState.ui.newFilePopoverOpen = false

    const folderName = prompt("Enter folder name:")
    if (!folderName) return

    const currentPath = filesState.path === '/' ? '' : filesState.path
    const targetPath = `${currentPath}/${folderName}`
    console.log(`targetPath`, targetPath)
    console.log(`currentPath`, currentPath)

    const response = await safeFetch('/api/v1/folder/create', {
        method: 'POST',
        body: formData({ path: targetPath })
    })
    if (response.failed) {
        handleErr({
            description: "Failed to create folder",
            notification: "Failed to create folder.",
        })
        return
    }

    const status = response.code
    const json = response.json()

    if (status.notFound) {
        handleErr({
            description: "Parent folder not found when creating folder",
            notification: "This folder was not found."
        })
        return
    } else if (status.failed) {
        handleErr({
            description: "Failed to create folder.",
            notification: json.message || "Failed to create folder.",
            isServerDown: status.serverDown
        })
        return
    }

    filesState.data.entries?.push({
        path: targetPath,
        filename: folderName,
        modifiedDate: unixNowMillis(),
        createdDate: unixNowMillis(),
        fileType: "FOLDER",
        size: 0,
        permissions: keysOf(filePermissionMeta),
        isExecutable: true,
        isWritable: true,
        shares: []
    })
}


export async function saveEditedFile() {
    if (!textFileViewerState.isFileSavable) return console.log(`File is not savable.`)
    if (!textFileViewerState.textEditor) return console.log(`Editor is null.`)

    const filePath = textFileViewerState.filePath
    if (!filePath) return console.log(`Edited file path is null.`)

    const doc = textFileViewerState.textEditor.state.doc
    const newContent = doc.toString()

    if (filesState.data.decodedContent === newContent) return console.log(`Content did not change.`)

    const response = await safeFetch(`/api/v1/file/edit`, {  
        body: formData({ path: filePath, content: newContent.toString() })
    })
    if (response.failed) {
        handleException(`Exception when editing file.`, `Failed to edit file.`, response.exception)
        return
    }

    const text = response.content
    const json = parseJson(text)
    
    if (response.code.failed) {
        handleErr({
            description: `Failed to edit file (${response.status}).`,
            notification: json.message || `Failed to edit file.`,
            isServerDown: response.code.serverDown
        })
        return
    }

    const modifiedDate = json.modifiedDate
    const size = json.size

    // Update file data
    if (filesState.path === filePath) {
        filesState.data.decodedContent = newContent
        filesState.data.fileMeta!.modifiedDate = modifiedDate
        filesState.data.fileMeta!.size = size
    }

    // Update folder data
    if (filesState.data.folderMeta && isPathDirectChild(filesState.data.folderMeta.path, filePath)) {
        filesState.data.entries!.forEach(entry => {
            if (entry.path === filePath) {
                entry.modifiedDate = modifiedDate
                entry.size = size
            }
        })
    }

    textFileViewerState.isFileSavable = false

    toast.success(`File saved.`)
}