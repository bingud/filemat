import type ConfirmDialog from "$lib/component/ConfirmDialog.svelte"
import type FolderTreeSelector from "../../../../routes/(app)/files/[...path]/content/elements/FolderTreeSelector.svelte"

// Confirm dialog state
class ConfirmDialogState {
    element: ConfirmDialog | undefined = $state()

    show(options: {
        title?: string,
        message?: string,
        confirmText?: string,
        cancelText?: string,
    } = {}) {
        return this.element?.show(options)
    }
}

export const confirmDialogState = new ConfirmDialogState()


// Folder selection dialog state
class FolderSelectorState {
    show: FolderTreeSelector["show"] | null = $state(null)
}

export const folderSelectorState = new FolderSelectorState()