<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte"
    import { linear } from "svelte/easing"
    import { fly } from "svelte/transition"

    let typeAhead = $derived(filesState.typeAhead)
    let matchCount = $derived(typeAhead.matchPaths.length)
</script>


{#if typeAhead.active}
    <div class="
        fixed z-overlay inset-x-0 bottom-0 pb-4 px-4 pointer-events-none flex justify-center
        {filesState.ui.detailsOpen ? "lg:pr-[calc(1rem+var(--spacing-details-sidebar))]" : ""}
    ">
        <div
            transition:fly={{ y: 8, duration: 100, easing: linear }}
            class="max-w-full flex items-center gap-3 rounded-full px-4 py-2 tabular-nums {matchCount > 0 ? 'bg-neutral-300 dark:bg-neutral-700' : 'bg-red-500 text-white'}"
        >
            <span class="truncate whitespace-pre">{typeAhead.query}</span>
            {#if matchCount > 0}
                <span class="shrink-0 opacity-60">{Math.max(typeAhead.matchIndex, 0) + 1}/{matchCount}</span>
            {/if}
        </div>
    </div>
{/if}
