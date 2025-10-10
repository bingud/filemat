import type ConfirmDialog from "$lib/component/ConfirmDialog.svelte"
import type InputDialog from "$lib/component/InputDialog.svelte"
import type FolderTreeSelector from "../../../../routes/(app)/files/[...path]/_elements/FolderTreeSelector.svelte"

// Confirm dialog state
class ConfirmDialogState {
    element: ConfirmDialog | undefined = $state()
    isOpen: boolean = $state(false)

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
    isOpen = $state(false)
}

export const folderSelectorState = new FolderSelectorState()



// Confirm dialog state
class InputDialogState {
    element: InputDialog | undefined = $state()
    isOpen: boolean = $state(false)

    show(options: {
        title?: string,
        message?: string,
        confirmText?: string,
        cancelText?: string,
    } = {}) {
        return this.element?.show(options)
    }
}

export const inputDialogState = new InputDialogState()