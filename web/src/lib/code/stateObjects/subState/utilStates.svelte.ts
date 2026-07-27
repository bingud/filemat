import type ConfirmDialog from "$lib/component/popover/ConfirmDialog.svelte"
import type InputDialog from "$lib/component/popover/InputDialog.svelte"
import type UploadConflictDialog from "$lib/component/popover/UploadConflictDialog.svelte"
import type FolderTreeSelector from "../../../../routes/(app)/(files)/files/[...path]/_elements/ui/FolderTreeSelector.svelte"

// Confirm dialog state
class ConfirmDialogState {
    element: ConfirmDialog | undefined = $state()
    isOpen: boolean = $state(false)

    show(options: {
        title?: string | null,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        alternateText: string,
    }): Promise<boolean | "alternate"> | undefined
    show(options?: {
        title?: string | null,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        alternateText?: null,
    }): Promise<boolean> | undefined
    show(options: {
        title?: string | null,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        alternateText?: string | null,
    } = {}) {
        return this.element?.show(options as any)
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
        type?: "text" | "password" | "number",
        defaultValue?: string,
    } = {}) {
        return this.element?.show(options)
    }
}

export const inputDialogState = new InputDialogState()


class UploadConflictDialogState {
    element: UploadConflictDialog | undefined = $state()
    isOpen: boolean = $state(false)

    show: UploadConflictDialog["show"] = (options) => {
        return this.element?.show(options) ?? Promise.resolve(null)
    }
}

export const uploadConflictDialogState = new UploadConflictDialogState()