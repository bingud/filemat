<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { toggleDarkMode } from "$lib/code/util/uiUtil";
    import { linear } from "svelte/easing";
    import { fade, fly } from "svelte/transition";
    import { appState } from '$lib/code/stateObjects/appState.svelte';

    function hide() { uiState.menuOpen = false }

    const transitionDuration = 150
</script>


<div class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex pointer-events-none lg:contents">
    <!-- Navbar -->
    {#if uiState.menuOpen || uiState.isDesktop}
        <div transition:fly={{ duration: transitionDuration, x: -400, opacity: 1 }} class="w-sidebar lg:w-sidebar-desktop h-full bg-surface pointer-events-auto z-10 flex flex-col justify-between shrink-0">
            <!-- Top -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.files}>All files</a>
                <a href="/home" on:click={hide} class="sidebar-button">Home folder</a>
                <a href="/accessible-files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.accessibleFiles}>Accessible to me</a>
                <a href="/shared-files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.allSharedFiles}>Shared files</a>
            </div>


            <!-- Bottom -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/settings/{uiState.settings.defaultSection}" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.settings}>Settings</a>
                <button on:click={toggleDarkMode} class="sidebar-button">{uiState.isDark ? "Dark" : "Light"} mode</button>
            </div>
        </div>
    {/if}

    <!-- Close menu button -->
    {#if uiState.menuOpen && !uiState.isDesktop}
        <button aria-label="Close menu" on:click={() => { uiState.menuOpen = false }} transition:fade={{ duration: transitionDuration, easing: linear }} class="absolute top-0 left-0 size-full bg-black/40 !cursor-default pointer-events-auto"></button>
    {/if}
</div>


<style lang="postcss">
    .sidebar-button {
        @apply flex items-center px-4 py-2 w-full rounded-md duration-[50ms] hover:bg-neutral-300 dark:hover:bg-neutral-800 select-none;
    }
    
    .current-button {
        @apply bg-neutral-300 dark:bg-neutral-800;
    }
</style>