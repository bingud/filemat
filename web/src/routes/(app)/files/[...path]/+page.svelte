<script lang="ts">
	import FolderIcon from '$lib/component/icons/FolderIcon.svelte';
    import { beforeNavigate, goto } from "$app/navigation"
    import { dynamicInterval, explicitEffect, letterS, pageTitle, unixNow } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { onDestroy, onMount } from "svelte"
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "./_code/breadcrumbState.svelte"
    import Breadcrumbs from './_elements/layout/Breadcrumbs.svelte';
    import DetailsSidebar from './_elements/layout/DetailsSidebar.svelte';
    import { createFilesState, destroyFilesState, filesState } from "$lib/code/stateObjects/filesState.svelte"
    import { fade, fly } from "svelte/transition"
    import { linear } from "svelte/easing"
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte"
    import { Popover } from "$lib/component/bits-ui-wrapper"
    import FileIcon from "$lib/component/icons/FileIcon.svelte"
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte"
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import NewFolderIcon from '$lib/component/icons/NewFolderIcon.svelte';
    import NewFileIcon from '$lib/component/icons/NewFileIcon.svelte';
    import TrashIcon from '$lib/component/icons/TrashIcon.svelte';
    import DownloadIcon from '$lib/component/icons/DownloadIcon.svelte';
    import MoveIcon from '$lib/component/icons/MoveIcon.svelte';
    import FileDropzone from './_elements/ui/FileDropzone.svelte';
    import { clientState } from '$lib/code/stateObjects/clientState.svelte';
    import FileBrowser from './_elements/FileBrowser/FileBrowser.svelte';
    import FileViewer from './_elements/layout/FileViewer.svelte';
    import { event_filesDropped, handleKeyDown, handleNewFile, handleUpload, loadPageData, recoverScrollPosition, reloadPageData, saveScrollPosition } from './_code/pageLogic';
    import { handleNewFolder, option_deleteSelectedFiles, option_downloadSelectedFiles, option_moveSelectedFiles } from './_code/fileActions';


    let {
        overrideTopLevelFolderUrlPath = null,
        requireFolderMeta = true,
    }: {
        overrideTopLevelFolderUrlPath?: string | null,
        requireFolderMeta?: boolean,
    } = $props()

    const filesStateNonce = createFilesState()
    const breadcrumbStateNonce = createBreadcrumbState()
    
    onMount(() => {
        window.addEventListener('keydown', handleKeyDown)

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
        }
    })

    onDestroy(() => {
        destroyFilesState(filesStateNonce)
        destroyBreadcrumbState(breadcrumbStateNonce)
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || "Files"))
    

    let lastDataLoadDate: number = unixNow()
    const pageDataPollingConfig = { idleDelay: 60, delay: 30 }
    let pollingInterval: ReturnType<typeof dynamicInterval> | null = null

    // Load page data when path changes
    explicitEffect(() => [ 
        filesState.path 
    ], () => {
        const path = filesState.path
        
        if (path) {
            filesState.abort()
            filesState.clearState()

            filesState.data.content = null

            if (path === "/") {
                filesState.scroll.pathPositions = {}
            }

            (path === "/" && overrideTopLevelFolderUrlPath 
                ? loadPageData(path, { overrideDataUrlPath: overrideTopLevelFolderUrlPath, dataType: "array" })
                : loadPageData(path, { dataType: "object" })
            ).then(() => {
                recoverScrollPosition()
            })
        }

        pollingInterval = dynamicInterval(() => {
            const meta = filesState.data.meta
            if (meta && (meta.fileType === "FOLDER" || (meta.fileType === "FOLDER_LINK" && appState.followSymlinks))) {
                if (filesState.path === path) {
                    reloadPageData()
                }
            }
        }, () => ((clientState.isIdle ? pageDataPollingConfig.idleDelay : pageDataPollingConfig.delay) * 1000))

        return () => { pollingInterval!.cancel() }
    })

    // Run when user stops being idle
    explicitEffect(() => [
        clientState.isIdle 
    ], () => {
        if (clientState.isIdle === false) {
            const now = unixNow()
            const elapsed = now - (lastDataLoadDate ?? 0)

            // Fetch page data if last fetch was too long ago
            if (elapsed > pageDataPollingConfig.delay) {
                pollingInterval?.reset()
                reloadPageData()
            }
        }
    })

    // Unselect entry when path changes
    explicitEffect(() => [ 
        filesState.selectedEntries.single, filesState.path
    ], () => {
        const selected = filesState.selectedEntries.single
        const current = filesState.path || "/"

        // if there’s a selection but it isn’t under the current directory, reset it
        if (selected && !selected.startsWith(current + (current === "/" ? "" : "/"))) {
            filesState.selectedEntries.reset()
        }
    })


    beforeNavigate(() => {
        saveScrollPosition()
    })

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
    <div class="w-full flex h-full min-h-0">
        <div bind:this={filesState.scroll.container} class="w-full {filesState.ui.detailsOpen ? 'w-[calc(100%-20rem)]' : 'w-full'} md:w-full flex flex-col h-full overflow-y-auto overflow-x-hidden custom-scrollbar md:gutter-stable-both">
            <!-- Header -->
            <div class="w-full shrink-0 flex flex-col px-2 items-center justify-between overflow-hidden rounded-lg py-2 bg-surface mt-2 gap-2">
                <!-- Top row -->
                <div bind:offsetWidth={breadcrumbState.containerWidth} class="w-full flex items-center">
                    <Breadcrumbs></Breadcrumbs>                    
                </div>

                <!-- Lower row -->
                <div class="w-full h-[2.5rem] flex items-center justify-between">
                    <!-- Left buttons -->
                    <div class="h-full flex items-center gap-2 py-[0.2rem]">
                        {#if filesState.selectedEntries.hasSelected === false}
                            <button on:click={handleNewFolder} title="Create a new folder inside this folder." class="action-button"><NewFolderIcon /></button>
                            <button on:click={handleNewFile} title="Create a new blank file inside this folder." class="action-button"><NewFileIcon /></button>
                        {:else}
                            <button on:click={option_downloadSelectedFiles} title="Download the selected files." class="action-button"><DownloadIcon /></button>
                            <button on:click={option_deleteSelectedFiles} title="Delete the selected files." class="action-button"><TrashIcon /></button>
                            <button on:click={option_moveSelectedFiles} title="Move the selected file{letterS(filesState.selectedEntries.count)}." class="action-button"><MoveIcon /></button>
                        {/if}
                    </div>

                    <!-- Right buttons -->
                    <div class="h-full flex items-center gap-2">
                        <Popover.Root bind:open={filesState.ui.newFilePopoverOpen}>
                            <Popover.Trigger title="Create or upload a file or folder." class="h-full flex items-center justify-center" hidden={filesState.data.meta?.fileType.startsWith("FILE")}>
                                <div class="h-full flex items-center justify-center gap-2 bg-surface-button rounded-md px-4">
                                    <div class="h-[1.2rem]">
                                        <PlusIcon></PlusIcon>
                                    </div>
                                    <p>New</p>
                                </div>
                            </Popover.Trigger>
                            <Popover.Content align="end" class="relative z-50">
                                <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50">
                                    <button on:click={handleUpload} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                        <div class="size-5 flex-shrink-0">
                                            <PlusIcon />
                                        </div>
                                        <span>Upload</span>
                                    </button>
                                    <button on:click={handleNewFolder} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                        <div class="size-5 flex-shrink-0">
                                            <FolderIcon />
                                        </div>
                                        <span>Folder</span>
                                    </button>
                                    <button on:click={handleNewFile} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                        <div class="size-5 flex-shrink-0">
                                            <FileIcon />
                                        </div>
                                        <span>File</span>
                                    </button>
                                </div>
                            </Popover.Content>
                        </Popover.Root>

                        <button title="Toggle the file details sidebar." on:click={() => { filesState.ui.toggleSidebar() }} class="action-button">
                            <InfoIcon />
                        </button>
                    </div>
                </div>
            </div>

            <!-- Files -->
            <div class="h-[calc(100%-3rem)] w-full">
                {#if !filesState.metaLoading && (!requireFolderMeta || filesState.data.meta)}
                    {#if filesState.data.sortedEntries && (!filesState.data.meta || filesState.data.meta.fileType === "FOLDER" || (filesState.data.meta.fileType === "FOLDER_LINK" && appState.followSymlinks))}
                        <FileBrowser />
                    {:else}
                        <div class="center">
                            <FileViewer />
                        </div>                    
                    {/if}
                {:else if !filesState.metaLoading && !filesState.data.meta}
                    <div class="center">
                        <p class="text-xl">Failed to load this file.</p>
                    </div>
                {:else}
                    <div class="center">
                        <Loader></Loader>
                    </div>
                {/if}
            </div>
        </div>


        <div class="contents">
            <!-- File info sidebar -->
            {#if filesState.ui.detailsOpen}
                <div on:click={filesState.unselect} class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex justify-end pointer-events-none md:contents min-h-0">
                    <div transition:fly={{ duration: 150, x: 400, opacity: 1 }} class="flex flex-col h-full max-w-full w-details-sidebar shrink-0 pointer-events-auto min-h-0">
                        <DetailsSidebar />
                    </div>
                </div>
            {/if}

            <!-- Close sidebar background button -->
            {#if !uiState.isDesktop && filesState.ui.detailsOpen}
                <button aria-label="Close details sidebar" on:click={() => { filesState.ui.detailsOpen = false }} transition:fade={{ duration: 150, easing: linear }} class="absolute top-0 left-0 size-full bg-black/40 !cursor-default pointer-events-auto"></button>
            {/if}
        </div>
    </div>
</div>

<FileDropzone on:filesDropped={event_filesDropped}></FileDropzone>


<style>
    @import "/src/app.css" reference;


    .action-button {
        @apply h-full aspect-square flex items-center justify-center rounded-lg p-2 bg-surface-button;
    }
</style>