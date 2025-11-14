<script lang="ts">
	import '../app.css';
    import {browser, dev} from "$app/environment"
    import { Toaster } from '@jill64/svelte-toast'
    import Symbols from '$lib/component/icons/util/Symbols.svelte';
    import { onMount } from 'svelte';
    import { debounceFunction } from '$lib/code/util/codeUtil.svelte';
    import { loadDarkModeState, saveDarkModeState, updateScreenSize } from '$lib/code/util/uiUtil';
    import { uiState } from '$lib/code/stateObjects/uiState.svelte';
    import { appState } from '$lib/code/stateObjects/appState.svelte';
    import { page } from '$app/state';
    import { uploadState } from '$lib/code/stateObjects/subState/uploadState.svelte';
    import ConfirmDialog from '$lib/component/popover/ConfirmDialog.svelte';
    import { confirmDialogState, inputDialogState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import InputDialog from '$lib/component/InputDialog.svelte';
    import { onUserIdleChange } from '$lib/code/util/stateUtils';
    import { Tooltip } from 'bits-ui';

	let { children } = $props();

    const palette = {}
    const toastOptions = {
        duration: 5000,
        position: "bottom-right"
    } as any

    onMount(() => {        
        loadDarkModeState()
        updateScreenSize()

        window.addEventListener('beforeunload', (e) => {
            if (uploadState.counts.uploading > 0) {
                e.preventDefault()
                e.returnValue = ""
            }
        })

        onUserIdleChange({
            onIdle: () => {},
            onActive: () => {},
        })
    })

    const updateScreenSizeDebounced = debounceFunction(updateScreenSize, 50, 1000)
    function onResize() {
        updateScreenSizeDebounced()
    }

    /**
     * Sync dark mode state
    */
    $effect(() => {
        if (!browser) return
        const html = document.documentElement
        if (!html) return

        if (uiState.isDark) {
            html.setAttribute("data-theme", "dark")
        } else {
            html.setAttribute("data-theme", "light")
        }

        saveDarkModeState()
    })

    /**
     * Effect to set whether the initial page is still open
    */
    let cancelInitialPageEffect = $effect.root(() => {
        let initialPath = page.url.pathname
        $effect(() => {
            const path = page.url.pathname

            if (initialPath) {
                if (path !== initialPath) {
                    appState.isInitialPageOpen = false
                    setTimeout(() => cancelInitialPageEffect(), 0)
                }
            }
            initialPath = path
        })
    })
</script>

<svelte:window on:resize={onResize} />

<Symbols />
<Toaster {palette} {toastOptions} />

<div id="root-page" class="w-full min-h-0 h-full flex flex-col">
    <Tooltip.Provider>
        {@render children()}
    </Tooltip.Provider>
</div>

<!-- Confirmation Dialog -->
<ConfirmDialog bind:this={confirmDialogState.element} />
<InputDialog bind:this={inputDialogState.element}></InputDialog>


{#if dev}
    <div class="fixed z-30 left-0 top-1/2 flex h-12 w-12 items-center justify-center bg-neutral-200 text-lg font-medium text-black pointer-events-none">
        <span class="block xs:hidden">-</span>
        <span class="hidden xs:block sm:hidden">XS</span>
        <span class="hidden sm:block md:hidden">SM</span>
        <span class="hidden md:block lg:hidden">MD</span>
        <span class="hidden lg:block xl:hidden">LG</span>
        <span class="hidden xl:block 2xl:hidden">XL</span>
        <span class="hidden 2xl:block">2XL</span>
    </div>
{/if}