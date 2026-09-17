<script lang="ts">
    import { cancelAllDownloads, cancelDownload, cancelDownloadJob } from "$lib/code/module/files/files"
    import { downloadState, type DownloadJob, type FileDownload } from "$lib/code/stateObjects/subState/downloadState.svelte"
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
    import { filenameFromPath, formatBytes } from "$lib/code/util/codeUtil.svelte"
    import ChevronDownIcon from "$lib/component/icons/ChevronDownIcon.svelte"
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte"

    const counts = $derived(downloadState.counts)

    async function close() {
        if (downloadState.hasBlockingDownloads) {
            const activeCount = counts.downloading + counts.queued
            const confirmed = await confirmDialogState.show({
                title: `Cancel all downloads?`,
                message: activeCount <= 1
                    ? `This will cancel the active download.`
                    : `This will cancel ${activeCount} downloads.`,
                confirmText: `Cancel downloads`,
                cancelText: `Keep downloading`,
            })
            if (confirmed !== true) return
            await cancelAllDownloads()
            downloadState.panelOpen = false
            return
        }

        downloadState.panelOpen = false
        downloadState.clearFinished()
    }

    function toggleExpanded() {
        downloadState.panelExpanded = !downloadState.panelExpanded
    }

    function toggleJobExpanded(job: DownloadJob) {
        job.expanded = !job.expanded
    }

    async function jobCloseButton(job: DownloadJob) {
        const stats = downloadState.jobStats(job.id)
        const inProgress = stats.downloading > 0 || stats.queued > 0 || job.listing
        if (!inProgress) {
            downloadState.removeJob(job.id)
            return
        }

        const confirmed = await confirmDialogState.show({
            title: `Cancel download?`,
            message: job.kind === `folder`
                ? `Cancel download of "${job.displayPath}" and its files?`
                : `Cancel download of "${job.displayPath}"?`,
            confirmText: `Cancel download`,
            cancelText: `Keep`,
        })
        if (!confirmed) return

        cancelDownloadJob(job.id)
    }

    async function downloadCloseButton(dl: FileDownload) {
        if (dl.status === `success` || dl.status === `canceled` || dl.status === `failed` || dl.status === `skipped`) {
            downloadState.removeFile(dl.path)
            const remaining = downloadState.filesForJob(dl.jobId)
            const job = downloadState.getJob(dl.jobId)
            if (!remaining.length && !job?.listing) downloadState.removeJob(dl.jobId)
            return
        }

        const name = dl.displayPath || filenameFromPath(dl.path)
        const confirmed = await confirmDialogState.show({
            title: `Cancel download?`,
            message: `Cancel download of "${name}"?`,
            confirmText: `Cancel download`,
            cancelText: `Keep`,
        })
        if (!confirmed) return

        cancelDownload(dl)
    }

    async function showPathDialog(path: string) {
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

    function statusLabel(status: FileDownload[`status`]) {
        if (status === `success`) return `Saved`
        if (status === `failed`) return `Failed`
        if (status === `canceled`) return `Canceled`
        if (status === `skipped`) return `Skipped`
        if (status === `downloading`) return `Downloading`
        return `Queued`
    }
</script>

<style>
    .panel-container {
        --panel-max-height: 25rem;
        --panel-header-height: 3rem;
        --panel-content-max-height: calc(
            var(--panel-max-height) - var(--panel-header-height)
        );

        max-height: min(var(--panel-max-height), 100%);
    }

    .panel-header {
        height: var(--panel-header-height);
    }
    .panel-content {
        max-height: var(--panel-content-max-height);
    }
</style>


<div class="panel-container w-full flex flex-col bg-surface rounded-lg overflow-hidden">
    <div class="panel-header w-full flex items-center justify-between px-3 py-2 bg-surface-content">
        <div class="">
            {#if counts.downloading > 0}<span>{counts.downloading} downloading</span><span class="last:hidden">,</span>{/if}
            {#if counts.successful > 0}<span>{counts.successful} saved</span><span class="last:hidden">,</span>{/if}
            {#if counts.failed > 0}<span>{counts.failed} failed</span><span class="last:hidden">,</span>{/if}
            {#if counts.canceled > 0}<span>{counts.canceled} canceled</span><span class="last:hidden">,</span>{/if}
            {#if counts.skipped > 0}<span>{counts.skipped} skipped</span><span class="last:hidden">,</span>{/if}
            {#if counts.queued > 0}<span>{counts.queued} queued</span><span class="last:hidden">,</span>{/if}
        </div>

        <div class="flex items-center gap-1">
            <button onclick={toggleExpanded} class="aspect-square p-2 h-8 rounded dark:hover:bg-neutral-700 disabled:opacity-50" class:rotate-180={!downloadState.panelExpanded}>
                <ChevronDownIcon />
            </button>

            <button onclick={close} class="aspect-square p-2 h-8 rounded dark:hover:bg-neutral-700">
                <CloseIcon />
            </button>
        </div>
    </div>

    {#if downloadState.panelExpanded}
        <div class="panel-content flex flex-col w-full overflow-auto custom-scrollbar">
            {#each downloadState.list as job (job.id)}
                {@const stats = downloadState.jobStats(job.id)}
                {@const files = downloadState.filesForJob(job.id)}
                {@const single = job.kind === `file` ? files[0] : null}

                <div class="w-full flex justify-between items-center px-3 min-h-10 py-1 gap-3">
                    <div class="flex items-center h-full grow min-w-0 overflow-hidden gap-1">
                        {#if job.kind === `folder`}
                            <button
                                type="button"
                                onclick={() => { toggleJobExpanded(job) }}
                                class="aspect-square p-0.5 size-5 rounded dark:hover:bg-neutral-700 shrink-0"
                                class:rotate-180={job.expanded}
                                title={job.expanded ? `Collapse` : `Expand`}
                            >
                                <ChevronDownIcon />
                            </button>
                        {/if}

                        <button
                            type="button"
                            class="truncate max-w-full text-left hover:underline"
                            onclick={() => { showPathDialog(job.id) }}
                        >
                            {job.displayPath || filenameFromPath(job.id)}
                        </button>
                    </div>

                    <div class="h-full flex items-center gap-3 shrink-0">
                        {#if job.kind === `folder`}
                            <div class="text-end text-sm leading-tight">
                                <p class="whitespace-nowrap">{stats.successful}/{stats.total} files</p>
                                {#if stats.downloading > 0 || stats.queued > 0}
                                    <p class="whitespace-nowrap opacity-80">{formatBytes(stats.bytesDownloaded)} / {formatBytes(stats.bytesTotal)}</p>
                                {:else}
                                    <p class="whitespace-nowrap" class:text-red-400={stats.status === `failed`}>
                                        {statusLabel(stats.status)}
                                    </p>
                                {/if}
                            </div>
                        {:else if single}
                            {#if single.status === `downloading` || single.status === `queued`}
                                <p class="whitespace-nowrap">{formatBytes(single.bytesDownloaded)} / {formatBytes(single.bytesTotal)}</p>
                            {:else}
                                <div class="text-end">
                                    <p class:text-red-400={single.status === `failed`}>{statusLabel(single.status)}</p>
                                </div>
                            {/if}
                        {/if}

                        <div class="w-[2.1rem] h-8 flex justify-end">
                            <button onclick={() => { jobCloseButton(job) }} class="size-8 p-2 dark:hover:bg-neutral-800 rounded">
                                <CloseIcon></CloseIcon>
                            </button>
                        </div>
                    </div>
                </div>

                {#if job.kind === `folder` && job.expanded}
                    {#each files as dl (dl.path)}
                        <div class="w-full flex justify-between items-center pl-5 pr-3 h-9 gap-3 bg-surface-content/40">
                            <div class="flex items-center h-full grow min-w-0 overflow-hidden">
                                <button
                                    type="button"
                                    class="truncate max-w-full text-left text-sm hover:underline opacity-90"
                                    onclick={() => { showPathDialog(dl.path) }}
                                >
                                    {dl.displayPath || filenameFromPath(dl.path)}
                                </button>
                            </div>

                            <div class="h-full flex items-center gap-3 shrink-0">
                                {#if dl.status === `downloading` || dl.status === `queued`}
                                    <p class="whitespace-nowrap text-sm">{formatBytes(dl.bytesDownloaded)} / {formatBytes(dl.bytesTotal)}</p>
                                {:else}
                                    <div class="text-end text-sm">
                                        <p class:text-red-400={dl.status === `failed`}>{statusLabel(dl.status)}</p>
                                    </div>
                                {/if}

                                <div class="w-[2.1rem] h-8 flex justify-end">
                                    <button onclick={() => { downloadCloseButton(dl) }} class="size-8 p-2 dark:hover:bg-neutral-800 rounded">
                                        <CloseIcon></CloseIcon>
                                    </button>
                                </div>
                            </div>
                        </div>
                    {/each}
                {/if}

                <hr class="basic-hr last:hidden">
            {/each}
        </div>
    {/if}
</div>
