<script lang="ts">
    import { uploadState, type FileUpload } from "$lib/code/stateObjects/subState/uploadState.svelte";
    import { filenameFromPath, forEachObject, formatBytes } from "$lib/code/util/codeUtil.svelte";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import RetryIcon from "$lib/component/icons/RetryIcon.svelte";

    const counts = $derived(uploadState.counts)

    function close() {
        uploadState.panelOpen = false

        const uploads = uploadState.all
        forEachObject(uploads, (k, v) => {
            if (v.status !== "uploading") {
                uploadState.removeUpload(k)
            }
        })
    }

    function uploadCloseButton(up: FileUpload) {
        if (up.status === "success" || up.status === "canceled") {
            delete uploadState.all[up.path]
        } else {
            if (up.action === "canceling") return
            up.action = "canceling"

            // Cancel, delete partial upload
            up.upload.abort(true).then(() => {
                uploadState.removeUpload(up.path)

                const onAbort = (up.upload as any).onAbort
                if (onAbort && typeof onAbort === "function") {
                    onAbort()
                }
            })
        }
    }

    function retryUpload(up: FileUpload) {
        up.upload.start()
    }
</script>

<style>
    .panel-container {
        --panel-max-height: 25rem;
        --panel-header-height: 3rem;
        --panel-content-max-height: calc(
            var(--panel-max-height) - var(--panel-header-height)
        );
        
        max-height: min(--panel-max-height, 100%);
    }
    
    .panel-header {
        height: var(--panel-header-height);
    }
    .panel-content {
        max-height: var(--panel-content-max-height);
    }
</style>

<div class="panel-container w-full flex flex-col dark:bg-neutral-900 rounded-lg overflow-hidden">
    <!-- Header -->
    <div class="panel-header w-full flex items-center justify-between px-3 py-2 bg-neutral-850">
        <div class="">
            {#if counts.uploading > 0}<span>{counts.uploading} uploading</span><span class="last:hidden">,</span>{/if}
            {#if counts.successful > 0}<span>{counts.successful} uploaded</span><span class="last:hidden">,</span>{/if}
            {#if counts.failed > 0}<span>{counts.failed} failed</span><span class="last:hidden">,</span>{/if}
            {#if counts.paused > 0}<span>{counts.paused} paused</span><span class="last:hidden">,</span>{/if}
            {#if counts.queued > 0}<span>{counts.queued} queued</span><span class="last:hidden">,</span>{/if}
        </div>

        <div class="flex items-center gap-3">
            <button on:click={close} disabled={counts.uploading > 0} class="aspect-square p-2 h-[2rem] rounded dark:hover:bg-neutral-700 disabled:opacity-50">
                <CloseIcon></CloseIcon>
            </button>
        </div>
    </div>

    <!-- Uploads -->
    <div class="panel-content flex flex-col w-full overflow-auto custom-scrollbar">
        {#each uploadState.list as up}
            <div class="w-full flex justify-between items-center px-3 h-[2.5rem] gap-3">
                <div class="flex items-center h-full flex-grow min-w-0 overflow-hidden">
                    <p class="truncate max-w-full">{filenameFromPath(up.actualPath || up.path)}</p>
                </div>
                
                <div class="h-full flex items-center gap-3">
                    <!-- Upload status -->
                    {#if up.status === "uploading"}
                        <p class="whitespace-nowrap">{formatBytes(up.bytesUploaded)} / {formatBytes(up.bytesTotal)}</p>
                    {:else}
                        <div class="xw-[6rem] text-end">
                            {#if up.status === "success"}
                                <p>Uploaded</p>
                            {:else if up.status === "failed"}
                                <p class="text-red-400">Failed</p>
                            {:else if up.status === "canceled"}
                                <p>Canceled</p>
                            {/if}
                        </div>
                    {/if}

                    <!-- Buttons -->
                    <div class="w-[4.1rem] gap-[0.1rem] h-[2rem] flex justify-end">
                        {#if up.status === "failed"}
                            <button on:click={() => { retryUpload(up) }} class="size-[2rem] p-2 dark:hover:bg-neutral-800 rounded">
                                <RetryIcon></RetryIcon>
                            </button>
                        {/if}

                        <button on:click={() => { uploadCloseButton(up) }} class="size-[2rem] p-2 dark:hover:bg-neutral-800 rounded">
                            <CloseIcon></CloseIcon>
                        </button>
                    </div>
                </div>
            </div>
            
            <hr class="basic-hr last:hidden">
        {/each}
    </div>

    <div></div>
</div>