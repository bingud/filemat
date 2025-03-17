<script lang="ts">
    import { page } from "$app/state";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { isBlank, removeSpaces } from "$lib/code/util/codeUtil.svelte";
    import { toggleDarkMode } from "$lib/code/util/uiUtil";
    import { linear } from "svelte/easing";
    import { fade, fly } from "svelte/transition";

    let currentButton = $derived.by(() => {
        const path = page.url.pathname

        if (path === "/") {
            return "all"
        } else if (path.startsWith("/settings")) {
            return "settings"
        }

        return "whothefuckknows"
    })

    function hide() { uiState.menuOpen = false }

    const transitionDuration = 150
</script>


<div class="fixed top-0 left-0 w-full h-full overflow-hidden flex pointer-events-none md:contents">
    <!-- Navbar -->
    {#if uiState.menuOpen || uiState.isDesktop}
        <div transition:fly={{ duration: transitionDuration, x: -400, opacity: 1 }} class="w-sidebar md:w-sidebar-desktop h-full bg-layout pointer-events-auto z-10 flex flex-col justify-between shrink-0">
            <!-- Top -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/" on:click={hide} class="sidebar-button" class:current-button={currentButton === "all"}>All Files</a>
                <a href="/" on:click={hide} class="sidebar-button">Home Folder</a>
            </div>

            <!-- Bottom -->
            <div class="flex flex-col px-2 py-4 gap-1">
                <a href="/settings" on:click={hide} class="sidebar-button" class:current-button={currentButton === "settings"}>Settings</a>
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