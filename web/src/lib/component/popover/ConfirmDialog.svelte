<script lang="ts">
    import { confirmDialogState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import { Dialog } from '$lib/component/bits-ui-wrapper'
    import CustomDialog from './CustomDialog.svelte';

    // Component state
    let dialogTitle = $state('Confirm')
    let dialogMessage = $state('Are you sure?')
    let dialogConfirmText = $state('Yes')
    let dialogCancelText = $state('No')
    
    // Promise resolver functions
    let resolvePromise: ((value: boolean) => void) | null = $state(null)
    
    // Public API - returns a Promise that resolves to true (confirm) or false (cancel/close)
    export function show(options: {
        title?: string,
        message?: string,
        confirmText?: string,
        cancelText?: string,
    } = {}) {
        if (options.title) dialogTitle = options.title
        if (options.message) dialogMessage = options.message
        if (options.confirmText) dialogConfirmText = options.confirmText
        if (options.cancelText) dialogCancelText = options.cancelText
        
        confirmDialogState.isOpen = true;
        
        return new Promise<boolean>((resolve) => {
            resolvePromise = resolve
        });
    }

    function handleConfirm() {
        if (resolvePromise) resolvePromise(true)
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
>
    <div class="flex justify-end gap-2">
        <button 
            on:click={handleCancel}
            class="basic-button"
        >
            {dialogCancelText}
        </button>
        <button 
            on:click={handleConfirm} 
            class="basic-button bg-surface-content-button!"
        >
            {dialogConfirmText}
        </button>
    </div>
</CustomDialog>