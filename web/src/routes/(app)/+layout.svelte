<script lang="ts">
    import { goto } from '$app/navigation';
    import { appState } from '$lib/code/stateObjects/appState.svelte';
    import { auth } from '$lib/code/stateObjects/authState.svelte';
    import { onDestroy, onMount } from 'svelte';
    import Navbar from './components/Navbar.svelte';
    import Sidebar from './components/Sidebar.svelte';
    import { dev } from '$app/environment';
    import { uiState } from '$lib/code/stateObjects/uiState.svelte';
    import { fetchState } from '$lib/code/state/stateFetcher';

    let { children } = $props()
    let mounted: boolean | null = $state(null)

    onMount(() => {
        (async () => {
            const stateResult = await fetchState({ principal: true, roles: true, app: true, systemRoleIds: true })
            if (!stateResult) {
                mounted = false
                return
            }

            if (appState.isSetup === false) {
                await goto (`/setup`)
                return
            }
            if (auth.authenticated !== true) {
                await goto("/login")
                return
            }

            mounted = true
        })()

        if (dev) {
            uiState.menuOpen = true
        }
    })

    onDestroy(() => {
        auth.reset()
    })
</script>


{#if mounted}
    <div class="flex flex-col md:flex-row w-full h-full overflow-hidden">
        <nav class="contents">
            <!-- Mobile Top Bar -->
            <div class="contents md:hidden">
                <Navbar />
            </div>

            <!-- Sidebar -->
            <Sidebar />
        </nav>
        
        <main class="w-full h-full shrink overflow-auto">
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