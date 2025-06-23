<script lang="ts">
    import { inputDialogState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import { Dialog } from '$lib/component/bits-ui-wrapper'

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
        
        inputDialogState.isOpen = true;
        
        return new Promise<boolean>((resolve) => {
            resolvePromise = resolve
        });
    }

    function handleConfirm() {
        if (resolvePromise) resolvePromise(true)
        resolvePromise = null
        inputDialogState.isOpen = false
    }

    function handleCancel() {
        if (resolvePromise) resolvePromise(false)
        resolvePromise = null
        inputDialogState.isOpen = false
    }

    function handleClose() {
        if (inputDialogState.isOpen) {
            if (resolvePromise) resolvePromise(false)
            resolvePromise = null
            inputDialogState.isOpen = false
        }
    }
    
    // Handle dialog closure
    $effect(() => {
        if (!inputDialogState.isOpen && resolvePromise) {
            resolvePromise(false)
            resolvePromise = null
        }
    });
</script>

<Dialog.Root bind:open={inputDialogState.isOpen} onOpenChange={handleClose}>
    <Dialog.Content class="fixed left-[50%] top-[50%] z-50 grid w-full max-w-md translate-x-[-50%] translate-y-[-50%] gap-4 border-[1px] border-neutral-300 bg-neutral-50 p-6 shadow-md duration-200 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%] data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%] sm:rounded-sm md:w-full dark:border-neutral-700 dark:bg-neutral-800">
        <Dialog.Title class="text-lg font-semibold text-neutral-800 dark:text-neutral-50">
            {dialogTitle}
        </Dialog.Title>
        <Dialog.Description class="text-sm">
            {dialogMessage}
        </Dialog.Description>
        <div class="flex justify-end gap-2">
            <button 
                on:click={handleCancel}
                class="inline-flex h-10 items-center justify-center rounded-sm bg-neutral-200 px-4 py-2 text-sm font-medium text-neutral-700 transition-colors hover:bg-neutral-300 dark:bg-neutral-700 dark:text-neutral-50 dark:hover:bg-neutral-600"
            >
                {dialogCancelText}
            </button>
            <button 
                on:click={handleConfirm} 
                class="inline-flex h-10 items-center justify-center rounded-sm bg-neutral-600 px-4 py-2 text-sm font-medium text-neutral-50 transition-colors hover:bg-neutral-700 dark:bg-neutral-600 dark:hover:bg-neutral-500"
            >
                {dialogConfirmText}
            </button>
        </div>
    </Dialog.Content>
</Dialog.Root> 