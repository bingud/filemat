<script lang="ts">
    import { confirmDialogState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import CustomDialog from './CustomDialog.svelte';

    // Component state
    let dialogTitle: string | null = $state('Confirm')
    let dialogMessage = $state('Are you sure?')
    let dialogConfirmText = $state('Yes')
    let dialogCancelText = $state('No')
    let dialogAlternateText: string | null = $state(null)

    type ConfirmResult = boolean | "alternate"

    // Promise resolver functions
    let resolvePromise: ((value: ConfirmResult) => void) | null = $state(null)

    // Public API - returns true/false, or "alternate" when alternateText is provided
    export function show(options: {
        title?: string | null,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        alternateText: string,
    }): Promise<boolean | "alternate">
    export function show(options?: {
        title?: string | null,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        alternateText?: null,
    }): Promise<boolean>
    export function show(options: {
        title?: string | null,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        alternateText?: string | null,
    } = {}): Promise<ConfirmResult> {
        if (options.title !== undefined) dialogTitle = options.title
        if (options.message) dialogMessage = options.message
        if (options.confirmText) dialogConfirmText = options.confirmText
        if (options.cancelText) dialogCancelText = options.cancelText
        dialogAlternateText = options.alternateText ?? null

        confirmDialogState.isOpen = true;

        return new Promise<ConfirmResult>((resolve) => {
            resolvePromise = resolve
        });
    }

    function handleConfirm() {
        if (resolvePromise) resolvePromise(true)
        resolvePromise = null
        confirmDialogState.isOpen = false
    }

    function handleAlternate() {
        if (resolvePromise) resolvePromise(`alternate`)
        resolvePromise = null
        confirmDialogState.isOpen = false
    }

    function handleCancel() {
        if (resolvePromise) resolvePromise(false)
        resolvePromise = null
        confirmDialogState.isOpen = false
    }

    function handleClose() {
        if (confirmDialogState.isOpen) {
            if (resolvePromise) resolvePromise(false)
            resolvePromise = null
            confirmDialogState.isOpen = false
        }
    }

    // Handle dialog closure
    $effect(() => {
        if (!confirmDialogState.isOpen && resolvePromise) {
            resolvePromise(false)
            resolvePromise = null
        }
    });
</script>


<CustomDialog
    bind:isOpen={confirmDialogState.isOpen} 
    onOpenChange={handleClose}
    title={dialogTitle}
    description={dialogMessage}
    class="w-[30rem]!"
>
    <div class="flex justify-end gap-2 flex-wrap">
        <button 
            on:click={handleCancel}
            class="basic-button"
        >
            {dialogCancelText}
        </button>
        {#if dialogAlternateText}
            <button
                on:click={handleAlternate}
                class="basic-button"
            >
                {dialogAlternateText}
            </button>
        {/if}
        <button 
            on:click={handleConfirm} 
            class="basic-button bg-surface-content-button!"
        >
            {dialogConfirmText}
        </button>
    </div>
</CustomDialog>
