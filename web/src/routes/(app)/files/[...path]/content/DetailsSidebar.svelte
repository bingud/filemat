<script lang="ts">
    import { filenameFromPath, isBlank } from "$lib/code/util/codeUtil.svelte";
    import { filesState } from "./code/filesState.svelte";


</script>


<div class="size-full flex flex-col gap-4 py-6 px-6 bg-neutral-200 dark:bg-neutral-850">
    {#if filesState.metaLoading}
        <div></div>
    {:else if filesState.selectedEntry.meta || filesState.data.meta}
        {@const file = (filesState.selectedEntry.meta || filesState.data.meta)!}

        <div class="w-full flex flex-col">
            <h3 class="truncate text-lg">{filenameFromPath(file.filename)}</h3>
        </div>
    {:else}
        <div class="w-full h-full flex flex-col items-center pt-4">
            <p class="opacity-80">Select a file to see details</p>
        </div>
    {/if}
</div>