<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { linear } from "svelte/easing";
    import { fade, fly } from "svelte/transition";

    let {
        open = $bindable(),
        width = "w-sidebar lg:w-sidebar-desktop",
        classes = "",
        children,
        bottom
    }: {
        open: boolean;
        width?: string;
        classes?: string;
        children: any;
        bottom?: any;
    } = $props();

    const transitionDuration = 150;
</script>

<div class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex pointer-events-none lg:contents">
    {#if open || uiState.isDesktop}
        <div 
            transition:fly={{ duration: transitionDuration, x: -400, opacity: 1 }} 
            class="h-full bg-surface pointer-events-auto z-10 flex flex-col justify-between shrink-0 {width} {classes}"
        >
            <div class="flex flex-col px-2 py-4 gap-1">
                {@render children()}
            </div>

            {#if bottom}
                <div class="flex flex-col px-2 py-4 gap-1">
                    {@render bottom()}
                </div>
            {/if}
        </div>
    {/if}

    {#if open && !uiState.isDesktop}
        <button 
            aria-label="Close menu" 
            on:click={() => { open = false }} 
            transition:fade={{ duration: transitionDuration, easing: linear }} 
            class="absolute top-0 left-0 size-full bg-black/40 !cursor-default pointer-events-auto"
        ></button>
    {/if}
</div>