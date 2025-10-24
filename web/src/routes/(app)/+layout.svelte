<script lang="ts">
    import { goto } from '$app/navigation';
    import { appState } from '$lib/code/stateObjects/appState.svelte';
    import { auth } from '$lib/code/stateObjects/authState.svelte';
    import { onDestroy, onMount } from 'svelte';
    import Navbar from './_components/Navbar.svelte';
    import Sidebar from './_components/Sidebar.svelte';
    import { fetchState, startStateAutoSync } from '$lib/code/state/stateFetcher';
    import { uploadState } from '$lib/code/stateObjects/subState/uploadState.svelte';

    let { children } = $props()
    let mounted: boolean | null = $state(null)

    onMount(() => {
        (async () => {
            const stateResult = await fetchState(undefined)
            if (!stateResult) {
                mounted = false
                return
            }

            if (appState.isSetup === false) {
                await goto (`/setup`)
                return
            }
            if (auth.authenticated !== true || !auth.principal) {
                await goto("/login")
                return
            }

            mounted = true
            startStateAutoSync()
        })()

        window.addEventListener("beforeunload", beforeUnload)
    })

    onDestroy(() => {
        auth.reset()
    })

    function beforeUnload(e: BeforeUnloadEvent) {
        if (uploadState.counts.uploading > 0 || uploadState.counts.queued > 0) {
            e.preventDefault()
            e.returnValue = ""
        }
    }
    $inspect(appState.filesStateNonce)
</script>


{#if mounted}
    <div class="flex flex-col lg:flex-row w-full h-full overflow-hidden min-h-0">
        <nav class="contents">
            <!-- Mobile Top Bar -->
            <div class="contents lg:hidden">
                <Navbar />
            </div>

            <!-- Sidebar -->
            <Sidebar />
        </nav>
        
        <main id="app-page" class="w-full lg:w-without-sidebar-desktop h-without-navbar lg:h-full min-h-0">
            {@render children()}
        </main>
    </div>
{:else if mounted == null}
    <div class="w-full h-full flex flex-col items-center justify-center">
        <div class="loader"></div>
    </div>
{:else if mounted == false}
    <div class="page flex-col justify-center items-center gap-6">
        <h2 class="text-2xl">Failed to load Filemat.</h2>
        <button on:click={() => { location.reload() }} class="underline px-2 py-1">Reload</button>
    </div>
{/if}