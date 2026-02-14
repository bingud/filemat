<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { toggleDarkMode } from "$lib/code/util/uiUtil";
    import { linear } from "svelte/easing";
    import { fade, fly } from "svelte/transition";
    import { appState } from '$lib/code/stateObjects/appState.svelte';
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import HomeIcon from "$lib/component/icons/HomeIcon.svelte";
    import SaveIcon from "$lib/component/icons/SaveIcon.svelte";
    import BookmarkIcon from "$lib/component/icons/BookmarkIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import EyeIcon from "$lib/component/icons/EyeIcon.svelte";
    import ShareIcon from "$lib/component/icons/ShareIcon.svelte";
    import SettingsIcon from "$lib/component/icons/SettingsIcon.svelte";
    import MoonIcon from "$lib/component/icons/MoonIcon.svelte";
    import SunIcon from "$lib/component/icons/SunIcon.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";

    function hide() { uiState.menuOpen = false }

    const transitionDuration = 150
</script>


<div class="fixed z-sidebar top-0 left-0 w-full h-full overflow-hidden flex pointer-events-none lg:contents">
    <!-- Navbar -->
    {#if uiState.menuOpen || uiState.isDesktop}
        <div transition:fly={{ duration: transitionDuration, x: -400, opacity: 1 }} class="w-sidebar lg:w-sidebar-desktop h-full bg-surface pointer-events-auto z-10 flex flex-col justify-between shrink-0">
            <!-- Top -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.files && !appState.currentPath.home}>
                    <span class="button-icon">
                        <FolderIcon />
                    </span>
                    <span>All</span>
                </a>
                <a href="/files{auth.principal?.homeFolderPath || ""}" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.home} class:pointer-events-none={!auth.principal?.homeFolderPath}>
                    <span class="button-icon">
                        <HomeIcon />
                    </span>
                    <span>Home folder</span>
                </a>
                <a href="/saved-files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.savedFiles}>
                    <span class="button-icon"><BookmarkIcon /></span>
                    <span>Saved</span>
                </a>
                <a href="/accessible-files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.accessibleFiles}>
                    <span class="button-icon"><EyeIcon /></span>
                    <span>Accessible to me</span>
                </a>
                <a href="/shared-files" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.allSharedFiles}>
                    <span class="button-icon"><ShareIcon /></span>
                    <span>Shared</span>
                </a>
            </div>


            <!-- Bottom -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/settings/{uiState.settings.defaultSection}" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.settings}>
                    <span class="button-icon"><SettingsIcon /></span>
                    <span>Settings</span>
                </a>
                <button on:click={toggleDarkMode} class="sidebar-button">
                    {#if uiState.isDark}
                        <span class="button-icon"><SunIcon /></span>
                        <span>Light mode</span>
                    {:else}
                        <span class="button-icon"><MoonIcon /></span>
                        <span>Dark mode</span>
                    {/if}
                </button>
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
        @apply flex items-center px-4 py-2 gap-3 w-full rounded-md duration-[50ms] hover:bg-neutral-300 dark:hover:bg-neutral-800 select-none;
    }

    .button-icon {
        @apply size-[1.1rem] opacity-70;
    }
    
    .current-button {
        @apply bg-neutral-300 dark:bg-neutral-800;
    }
</style>