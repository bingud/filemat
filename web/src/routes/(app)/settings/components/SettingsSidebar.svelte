<script lang="ts">
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { linear } from "svelte/easing";
    import { fade, fly } from "svelte/transition";
    import { settingSectionLists } from "../settings";
    import { goto } from "$app/navigation";

    export let classes: string

    async function openSection(section: typeof uiState.settings.section) {
        await goto(`/settings/${section}`)
        uiState.settings.section = section
        uiState.settings.menuOpen = false
    }

    const transitionDuration = 150
</script>


<div class="fixed top-0 left-0 w-full h-full overflow-hidden flex pointer-events-none md:contents">
    <!-- Navbar -->
    {#if uiState.settings.menuOpen || uiState.isDesktop}
        <div transition:fly={{ duration: transitionDuration, x: -400, opacity: 1 }} class="w-sidebar md:w-sidebar-desktop h-full bg-layout pointer-events-auto z-10 flex flex-col justify-between shrink-0 {classes}">
            <!-- Top -->
            <div class="flex flex-col px-2 py-4 gap-1">
                {#each Object.values(settingSectionLists) as sections, index}
                    {#if index !== 1 || auth.isAdmin}
                        {#if index === 1}
                            <p class="w-full px-4 border-b border-neutral-700 my-4 py-2 font-medium">Admin Settings</p>
                        {/if}
                        {#each sections as section}
                            <a href="/settings/{section}" on:click|preventDefault={() => { openSection(section) }} class="flex items-center px-4 py-2 w-full rounded-md duration-[50ms] hover:bg-neutral-300 dark:hover:bg-neutral-800 select-none capitalize {uiState.settings.section === section ? 'bg-neutral-300 dark:bg-neutral-800' : ''} active:text-blue-400">{section}</a>
                        {/each}
                    {/if}
                {/each}
            </div>

            <!-- Bottom -->
            <div class="flex flex-col px-2 py-4 gap-1">
            </div>
        </div>
    {/if}

    <!-- Close menu button -->
    {#if uiState.settings.menuOpen && !uiState.isDesktop}
        <button aria-label="Close menu" on:click={() => { uiState.settings.menuOpen = false }} transition:fade={{ duration: transitionDuration, easing: linear }} class="absolute top-0 left-0 size-full bg-black/40 !cursor-default pointer-events-auto"></button>
    {/if}
</div>