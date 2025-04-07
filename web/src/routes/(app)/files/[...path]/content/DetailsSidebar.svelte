<script lang="ts">
    import { filenameFromPath, formatBytes, formatUnixMillis, isBlank } from "$lib/code/util/codeUtil.svelte";
    import { filesState } from "./code/filesState.svelte";


</script>


<div class="size-full flex flex-col gap-6 py-6 bg-neutral-200 dark:bg-neutral-850">
    {#if filesState.metaLoading}
        <div></div>
    {:else if filesState.selectedEntry.meta || filesState.data.meta}
        {@const file = (filesState.selectedEntry.meta || filesState.data.meta)!}

        <div class="w-full flex flex-col px-6">
            <h3 class="truncate text-lg">{filenameFromPath(file.filename) || "/"}</h3>
        </div>

        <hr class="basic-hr">

        <div class="w-full flex flex-col px-6 gap-6">
            <div class="detail-container">
                <p class="detail-title">File Size</p>
                <p>{formatBytes(file.size)}</p>
            </div>

            <div class="detail-container">
                <p class="detail-title">Last modified at</p>
                <p>{formatUnixMillis(file.modifiedDate)}</p>
            </div>

            <div class="detail-container">
                <p class="detail-title">Created at</p>
                <p>{formatUnixMillis(file.createdDate)}</p>
            </div>
        </div>
    {:else}
        <div class="w-full h-full flex flex-col items-center pt-4">
            <p class="opacity-80">Select a file to see details</p>
        </div>
    {/if}
</div>


<style>
    @import "/src/app.css" reference;

    .detail-container {
        @apply flex flex-col;
    }

    .detail-title {
        @apply text-sm opacity-80;
    }
</style>