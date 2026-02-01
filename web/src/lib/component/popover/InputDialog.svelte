<script lang="ts">
    import { inputDialogState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import { macrotask } from '$lib/code/util/codeUtil.svelte';
    import { Dialog } from '$lib/component/bits-ui-wrapper'
    import CustomDialog from './CustomDialog.svelte';

    // Component state
    let dialogType = $state('text')
    let dialogTitle = $state('Enter Text')
    let dialogMessage = $state('Please enter your text:')
    let dialogConfirmText = $state('Confirm')
    let dialogCancelText = $state('Cancel')
    let dialogPlaceholder = $state('')
    let inputValue = $state('')
    let inputElement: HTMLInputElement
    
    // Promise resolver functions
    let resolvePromise: ((value: string | null) => void) | null = $state(null)
    
    // Public API - returns a Promise that resolves to string (input text) or null (cancel/close)
    export function show(options: {
        title?: string,
        message?: string,
        confirmText?: string,
        cancelText?: string,
        placeholder?: string,
        defaultValue?: string,
        type?: "text" | "password" | "number",
    } = {}) {
        if (options.title) dialogTitle = options.title
        if (options.message) dialogMessage = options.message
        if (options.confirmText) dialogConfirmText = options.confirmText
        if (options.cancelText) dialogCancelText = options.cancelText
        if (options.placeholder) dialogPlaceholder = options.placeholder
        if (options.type) dialogType = options.type
        
        // Set default value or clear input
        inputValue = options.defaultValue || ''
        
        inputDialogState.isOpen = true
        
        return new Promise<string | null>((resolve) => {
            resolvePromise = resolve
        })
    }

    function handleConfirm() {
        if (resolvePromise) resolvePromise(inputValue.trim())
        resolvePromise = null
        inputDialogState.isOpen = false
        inputValue = ''
    }

    function handleCancel() {
        if (resolvePromise) resolvePromise(null)
        resolvePromise = null
        inputDialogState.isOpen = false
        inputValue = ''
    }

    function handleClose() {
        if (inputDialogState.isOpen) {
            if (resolvePromise) resolvePromise(null)
            resolvePromise = null
            inputDialogState.isOpen = false
            inputValue = ''
        }
    }
    
    function handleKeydown(event: KeyboardEvent) {
        if (event.key === 'Enter') {
            event.preventDefault()
            handleConfirm()
        } else if (event.key === 'Escape') {
            event.preventDefault()
            handleCancel()
        }
    }
    
    // Handle dialog opening and closure
    $effect(() => {
        if (inputDialogState.isOpen && inputElement) {
            macrotask(() => {
                inputElement.focus()
            })
        }

        if (!inputDialogState.isOpen && resolvePromise) {
            resolvePromise(null)
            resolvePromise = null
            inputValue = ''
        }
    })
</script>


<CustomDialog 
    bind:isOpen={inputDialogState.isOpen} 
    onOpenChange={handleClose}
    title={dialogTitle}
    description={dialogMessage}
>
    <input
        bind:value={inputValue}
        bind:this={inputElement}
        placeholder={dialogPlaceholder}
        on:keydown={handleKeydown}
        type={dialogType}
        class="basic-input-light"
        autofocus
    />
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