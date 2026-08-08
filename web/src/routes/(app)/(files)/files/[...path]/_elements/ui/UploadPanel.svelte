<script lang="ts">
    import { cancelAllUploads, cancelUpload, pauseAllUploads, pauseUpload, resumeAllUploads, resumeUpload, retryTusUpload } from "$lib/code/module/files/files";
    import { uploadState, type FileUpload } from "$lib/code/stateObjects/subState/uploadState.svelte";
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { filenameFromPath, forEachObject, formatBytes } from "$lib/code/util/codeUtil.svelte";
    import ChevronDownIcon from "$lib/component/icons/ChevronDownIcon.svelte";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import PauseIcon from "$lib/component/icons/PauseIcon.svelte";
    import PlayIcon from "$lib/component/icons/PlayIcon.svelte";
    import RetryIcon from "$lib/component/icons/RetryIcon.svelte";

    const counts = $derived(uploadState.counts)
    const showPauseAll = $derived(counts.uploading > 0 || counts.queued > 0)
    const showResumeAll = $derived(counts.paused > 0 || counts.incomplete > 0)

    function clearFinishedUploads() {
        const uploads = uploadState.all
        forEachObject(uploads, (k, v) => {
            if (
                v.status !== "uploading"
                && v.status !== "incomplete"
                && v.status !== "paused"
                && v.status !== "queued"
            ) {
                uploadState.removeUpload(k)
            }
        })
    }

    async function close() {
        if (uploadState.hasBlockingUploads) {
            const activeCount =
                counts.uploading
                + counts.queued
                + counts.paused
                + counts.incomplete
            const confirmed = await confirmDialogState.show({
                title: `Cancel all uploads?`,
                message: activeCount === 1
                    ? `This will cancel the active upload.`
                    : `This will cancel ${activeCount} uploads.`,
                confirmText: `Cancel uploads`,
                cancelText: `Keep uploading`,
            })
            if (confirmed !== true) return
            await cancelAllUploads()
            uploadState.panelOpen = false
            return
        }

        uploadState.panelOpen = false
        clearFinishedUploads()
    }

    function toggleExpanded() {
        uploadState.panelExpanded = !uploadState.panelExpanded
    }

    async function uploadCloseButton(up: FileUpload) {
        if (up.status === "success" || up.status === "canceled" || up.status === "skipped") {
            delete uploadState.all[up.path]
            return
        }

        if (up.action === "canceling" || up.action === "pausing") return

        const name = up.displayPath || filenameFromPath(up.actualPath || up.path)
        const confirmed = await confirmDialogState.show({
            title: `Cancel upload?`,
            message: `Cancel upload of "${name}"?`,
            confirmText: `Cancel upload`,
            cancelText: `Keep`,
        })
        if (!confirmed) return

        await cancelUpload(up)
    }

    function retryUpload(up: FileUpload) {
        retryTusUpload(up)
    }

    function onResumeUpload(up: FileUpload) {
        resumeUpload(up)
    }

    function onPauseUpload(up: FileUpload) {
        pauseUpload(up)
    }

    async function showUploadPathDialog(up: FileUpload) {
        const path = up.actualPath || up.path
        const copied = await confirmDialogState.show({
            title: null,
            message: path,
            confirmText: `Copy`,
            cancelText: `Close`,
        })
        if (copied) {
            navigator.clipboard.writeText(path)
        }
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


<div class="panel-container w-full flex flex-col bg-surface rounded-lg overflow-hidden">
    <!-- Header -->
    <div class="panel-header w-full flex items-center justify-between px-3 py-2 bg-surface-content">
        <div class="">
            {#if counts.uploading > 0}<span>{counts.uploading} uploading</span><span class="last:hidden">,</span>{/if}
            {#if counts.incomplete > 0}<span>{counts.incomplete} incomplete</span><span class="last:hidden">,</span>{/if}
            {#if counts.successful > 0}<span>{counts.successful} uploaded</span><span class="last:hidden">,</span>{/if}
            {#if counts.failed > 0}<span>{counts.failed} failed</span><span class="last:hidden">,</span>{/if}
            {#if counts.skipped > 0}<span>{counts.skipped} skipped</span><span class="last:hidden">,</span>{/if}
            {#if counts.paused > 0}<span>{counts.paused} paused</span><span class="last:hidden">,</span>{/if}
            {#if counts.queued > 0}<span>{counts.queued} queued</span><span class="last:hidden">,</span>{/if}
        </div>

        <div class="flex items-center gap-1">
            {#if showResumeAll}
                <button
                    type="button"
                    title="Resume all"
                    on:click={() => { resumeAllUploads() }}
                    class="aspect-square p-2 h-8 rounded dark:hover:bg-neutral-700"
                >
                    <PlayIcon />
                </button>
            {/if}

            {#if showPauseAll}
                <button
                    type="button"
                    title="Pause all"
                    on:click={() => { pauseAllUploads() }}
                    class="aspect-square p-2 h-8 rounded dark:hover:bg-neutral-700"
                >
                    <PauseIcon />
                </button>
            {/if}

            <button on:click={toggleExpanded} class="aspect-square p-2 h-8 rounded dark:hover:bg-neutral-700 disabled:opacity-50" class:rotate-180={!uploadState.panelExpanded}>
                <ChevronDownIcon />
            </button>

            <button on:click={close} class="aspect-square p-2 h-8 rounded dark:hover:bg-neutral-700">
                <CloseIcon />
            </button>
        </div>
    </div>

    <!-- Uploads -->
    {#if uploadState.panelExpanded}
        <div class="panel-content flex flex-col w-full overflow-auto custom-scrollbar">
            {#each uploadState.list as up}
                <div class="w-full flex justify-between items-center px-3 h-10 gap-3">
                    <div class="flex items-center h-full grow min-w-0 overflow-hidden">
                        <button
                            type="button"
                            class="truncate max-w-full text-left hover:underline"
                            on:click={() => { showUploadPathDialog(up) }}
                        >
                            {up.displayPath || filenameFromPath(up.actualPath || up.path)}
                        </button>
                    </div>
                    
                    <div class="h-full flex items-center gap-3">
                        <!-- Upload status -->
                        {#if up.status === "uploading" || up.status === "paused" || up.status === "incomplete" || up.status === "queued"}
                            <p class="whitespace-nowrap">{formatBytes(up.bytesUploaded)} / {formatBytes(up.bytesTotal)}</p>
                        {:else}
                            <div class="xw-[6rem] text-end">
                                {#if up.status === "success"}
                                    <p>Uploaded</p>
                                {:else if up.status === "failed"}
                                    <p class="text-red-400">Failed</p>
                                {:else if up.status === "canceled"}
                                    <p>Canceled</p>
                                {:else if up.status === "skipped"}
                                    <p>Skipped</p>
                                {/if}
                            </div>
                        {/if}

                        <!-- Buttons -->
                        <div class="w-[4.1rem] gap-[0.1rem] h-8 flex justify-end">
                            {#if up.status === "uploading"}
                                <button
                                    on:click={() => { onPauseUpload(up) }}
                                    disabled={up.action === "pausing"}
                                    class="size-8 p-2 dark:hover:bg-neutral-800 rounded disabled:opacity-50"
                                >
                                    <PauseIcon />
                                </button>
                            {:else if up.status === "paused" || up.status === "incomplete"}
                                <button
                                    on:click={() => { onResumeUpload(up) }}
                                    class="size-8 p-2 dark:hover:bg-neutral-800 rounded"
                                >
                                    <PlayIcon />
                                </button>
                            {:else if up.status === "failed"}
                                <button on:click={() => { retryUpload(up) }} class="size-8 p-2 dark:hover:bg-neutral-800 rounded">
                                    <RetryIcon></RetryIcon>
                                </button>
                            {/if}

                            <button on:click={() => { uploadCloseButton(up) }} class="size-8 p-2 dark:hover:bg-neutral-800 rounded">
                                <CloseIcon></CloseIcon>
                            </button>
                        </div>
                    </div>
                </div>
                
                <hr class="basic-hr last:hidden">
            {/each}
        </div>
    {/if}

    <div></div>
</div>
