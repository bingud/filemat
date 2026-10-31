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
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { filePageUrl } from "$lib/code/util/codeUtil.svelte"

    function hide() { uiState.menuOpen = false }

    const transitionDuration = 150

    // Collapsing only applies on desktop
    const collapsed = $derived(uiState.isDesktop && uiState.sidebarCollapsed)
</script>


<div class="fixed z-sidebar top-0 left-0 w-full h-full overflow-hidden flex pointer-events-none lg:contents">
    <!-- Navbar -->
    {#if uiState.menuOpen || uiState.isDesktop}
        <div transition:fly={{ duration: transitionDuration, x: -400, opacity: 1 }} class:collapsed class="w-sidebar {collapsed ? 'lg:w-sidebar-collapsed' : 'lg:w-sidebar-desktop'} h-full bg-surface pointer-events-auto z-10 flex flex-col justify-between shrink-0">
            <!-- Top -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/files" title="All" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.files && !appState.currentPath.home}>
                    <span class="button-icon">
                        <FolderIcon />
                    </span>
                    <span class="label">All</span>
                </a>
                <a href={filePageUrl(auth.principal?.homeFolderPath || ``)} title="Home folder" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.home} class:pointer-events-none={!auth.principal?.homeFolderPath}>
                    <span class="button-icon">
                        <HomeIcon />
                    </span>
                    <span class="label">Home folder</span>
                </a>
                <a href="/saved-files" title="Saved" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.savedFiles}>
                    <span class="button-icon"><BookmarkIcon /></span>
                    <span class="label">Saved</span>
                </a>
                <a href="/accessible-files" title="Accessible to me" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.accessibleFiles}>
                    <span class="button-icon"><EyeIcon /></span>
                    <span class="label">Accessible to me</span>
                </a>
                <a href="/shared-files" title="Shared" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.allSharedFiles}>
                    <span class="button-icon"><ShareIcon /></span>
                    <span class="label">Shared</span>
                </a>
            </div>


            <!-- Bottom -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/settings/{uiState.settings.defaultSection}" title="Settings" on:click={hide} class="sidebar-button" class:current-button={appState.currentPath.settings}>
                    <span class="button-icon"><SettingsIcon /></span>
                    <span class="label">Settings</span>
                </a>
                <button on:click={toggleDarkMode} title={uiState.isDark ? "Light mode" : "Dark mode"} class="sidebar-button">
                    {#if uiState.isDark}
                        <span class="button-icon"><SunIcon /></span>
                        <span class="label">Light mode</span>
                    {:else}
                        <span class="button-icon"><MoonIcon /></span>
                        <span class="label">Dark mode</span>
                    {/if}
                </button>
                {#if uiState.isDesktop}
                    <button on:click={() => { uiState.sidebarCollapsed = !uiState.sidebarCollapsed }} title={collapsed ? "Show" : "Hide"} class="sidebar-button">
                        <span class="button-icon">
                            {#if collapsed}
                                <ChevronRightIcon />
                            {:else}
                                <ChevronLeftIcon />
                            {/if}
                        </span>
                        <span class="label">Hide</span>
                    </button>
                {/if}
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
        @apply flex items-center px-4 h-10 gap-3 w-full shrink-0 rounded-md transition-colors duration-[50ms] hover:bg-neutral-300 dark:hover:bg-neutral-800 select-none;
    }

    .button-icon {
        @apply size-[1.1rem] shrink-0 opacity-70;
    }

    .current-button {
        @apply bg-neutral-300 dark:bg-neutral-800;
    }

    .collapsed .label {
        @apply hidden;
    }

    .collapsed .sidebar-button {
        @apply justify-center px-0 w-10;
    }
</style>
