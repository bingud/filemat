import { filePermissionMeta } from "$lib/code/data/permissions";
import { moveMultipleFiles, moveFile, deleteFiles, downloadFilesAsZip } from "$lib/code/module/files";
import { filesState } from "$lib/code/stateObjects/filesState.svelte";
import { confirmDialogState, folderSelectorState } from "$lib/code/stateObjects/subState/utilStates.svelte";
import { filenameFromPath, formData, handleErr, keysOf, resolvePath, safeFetch, unixNowMillis, valuesOf } from "$lib/code/util/codeUtil.svelte";


export async function option_moveSelectedFiles() {
    if (!filesState.selectedEntries.hasSelected || !filesState.data.meta) return

    const newParentPath = await folderSelectorState.show!({
        title: "Choose the target folder.",
        initialSelection: filesState.path
    })
    if (!newParentPath) return

    if (filesState.selectedEntries.hasMultiple) {
        moveMultipleFiles(newParentPath, filesState.selectedEntries.list)
    } else {
        const selected = filesState.selectedEntries.single!
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
        if (!filesState.selectedEntries.meta) return;

        const list = valuesOf(filesState.selectedEntries.meta).filter(v => !!v);
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
    })
}