<script lang="ts">
    import { beforeNavigate } from "$app/navigation"
    import { appendTrailingSlash, dynamicInterval, explicitEffect, isFolder, isPathDirectChild as isPathDirectChildOf, letterS, pageTitle, unixNow } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { onDestroy, onMount } from "svelte"
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "./_code/breadcrumbState.svelte"
    import Breadcrumbs from './_elements/layout/Breadcrumbs.svelte';
    import DetailsSidebar from './_elements/layout/DetailsSidebar.svelte';
    import { createFilesState, destroyFilesState, filesState } from "$lib/code/stateObjects/filesState.svelte"
    import { fade, fly } from "svelte/transition"
    import { linear } from "svelte/easing"
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import NewFolderIcon from '$lib/component/icons/NewFolderIcon.svelte';
    import NewFileIcon from '$lib/component/icons/NewFileIcon.svelte';
    import TrashIcon from '$lib/component/icons/TrashIcon.svelte';
    import DownloadIcon from '$lib/component/icons/DownloadIcon.svelte';
    import MoveIcon from '$lib/component/icons/MoveIcon.svelte';
    import FileDropzone from './_elements/ui/FileDropzone.svelte';
    import { clientState } from '$lib/code/stateObjects/clientState.svelte';
    import FileBrowser from './_elements/FileBrowser/FileBrowser.svelte';
    import FileViewer from './_elements/layout/FileViewer.svelte';
    import { event_filesDropped, handleKeyDown, handleNewFile, loadPageData, recoverScrollPosition, reloadCurrentFolder, saveScrollPosition } from './_code/pageLogic';
    import { handleNewFolder, option_deleteSelectedFiles, option_downloadSelectedFiles, option_moveSelectedFiles, saveEditedFile } from './_code/fileActions';
    import NewFileButton from './_elements/button/NewFileButton.svelte';
    import { fileViewType_getFromLocalstorage } from "$lib/code/util/uiUtil";
    import FileSortingButton from "./_elements/button/FileSortingButton.svelte";
    import FileViewTypeButton from "./_elements/button/FileViewTypeButton.svelte";
    import FileDetailsButton from "./_elements/button/FileDetailsButton.svelte";
    import { textFileViewerState } from "./_code/textFileViewerState.svelte";
    import SaveIcon from "$lib/component/icons/SaveIcon.svelte";


    let {
        overrideTopLevelFolderUrlPath = null,
    }: {
        overrideTopLevelFolderUrlPath?: string | null,
    } = $props()

    const filesStateNonce = createFilesState()
    const breadcrumbStateNonce = createBreadcrumbState()

    const pageDataPollingConfig = { idleDelay: 60, delay: 30 }
    let pollingInterval: ReturnType<typeof dynamicInterval> | null = null
    
    onMount(() => {
        window.addEventListener('keydown', handleKeyDown)
        filesState.ui.filePreviewLoader.setScrollContainer(filesState.scroll.container || null)

        const fileViewType = fileViewType_getFromLocalstorage()
        if (fileViewType) filesState.ui.fileViewType = fileViewType

        // Automatically refresh folder entries
        pollingInterval = dynamicInterval(() => {
            reloadCurrentFolder()
        }, () => ((clientState.isIdle ? pageDataPollingConfig.idleDelay : pageDataPollingConfig.delay) * 1000))

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
            pollingInterval?.cancel()
        }
    })

    onDestroy(() => {
        destroyFilesState(filesStateNonce)
        destroyBreadcrumbState(breadcrumbStateNonce)
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || "Files"))
    let lastDataLoadDate: number = unixNow()

    // Load page data when path changes
    explicitEffect(() => [ 
        filesState.path 
    ], () => {
        const newPath = filesState.path
        
        if (newPath) {
            filesState.abort()

            const folderMeta = filesState.data.folderMeta
            const pathIsChild = folderMeta ? isPathDirectChildOf(folderMeta.path, newPath) : false
            const pathIsParentFolder = folderMeta ? folderMeta.path === newPath : false

            if (pathIsChild || pathIsParentFolder) {
                filesState.clearOpenState()
            } else {
                filesState.clearAllState()
            }

            if (newPath === "/") {
                filesState.scroll.pathPositions = {}
            }

            // Do not load page data if navigating back to current parent folder
            // Use existing state
            if (pathIsParentFolder === false) {
                (newPath === "/" && overrideTopLevelFolderUrlPath 
                    ? loadPageData(newPath, { overrideDataUrlPath: overrideTopLevelFolderUrlPath, fileDataType: "array" })
                    : loadPageData(newPath, { fileDataType: "object", loadParentFolder: true })
                ).then(() => {
                    recoverScrollPosition()
                })

                if (!pathIsChild) pollingInterval?.reset()
            } else { queueMicrotask(recoverScrollPosition) }
        }

        return () => { pollingInterval?.cancel() }
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
                reloadCurrentFolder()
            }
        }
    })

    // Unselect entry when path changes
    explicitEffect(() => [ 
        filesState.selectedEntries.singlePath,
        filesState.path
    ], () => {
        const selected = filesState.selectedEntries.singlePath
        const current = filesState.path || "/"

        // if there’s a selection but it isn’t under the current directory, reset it
        if (selected && !selected.startsWith(appendTrailingSlash(current)) && selected !== current) {
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
        <div class="w-full {filesState.ui.detailsOpen ? 'w-[calc(100%-20rem)]' : 'w-full'} lg:w-full flex flex-col h-full">
            <!-- Header -->
            <div class="w-full shrink-0 lg:mt-2   overflow-y-auto custom-scrollbar lg:gutter-stable-both">
                <div class="w-full flex flex-col items-center justify-between gap-2 overflow-hidden bg-surface pb-2 lg:py-2 px-2 rounded-b-lg lg:rounded-lg">
                    <!-- Top row -->
                    <div bind:offsetWidth={breadcrumbState.containerWidth} class="w-full flex items-center">
                        <Breadcrumbs></Breadcrumbs>                    
                    </div>

                    <!-- Lower row -->
                    <div class="w-full h-[2.5rem] flex items-center justify-between">
                        <!-- Left buttons -->
                        <div class="h-full flex items-center gap-2">
                            {#if filesState.data.folderMeta && filesState.isFileListOpen &&
                                    (filesState.selectedEntries.hasSelected === false || filesState.selectedEntries.isCurrentPathSelected)
                            }
                                <button on:click={handleNewFolder} title="Create a new folder inside this folder" class="file-action-button"><NewFolderIcon /></button>
                                <button on:click={handleNewFile} title="Create a new blank file inside this folder" class="file-action-button"><NewFileIcon /></button>
                            {:else if filesState.selectedEntries.hasSelected}
                                <button on:click={option_downloadSelectedFiles} title="Download the selected files" class="file-action-button"><DownloadIcon /></button>
                                <button on:click={option_deleteSelectedFiles} title="Delete the selected files" class="file-action-button"><TrashIcon /></button>
                                {#if filesState.data.fileMeta == null}
                                    <button on:click={option_moveSelectedFiles} title="Move the selected file{letterS(filesState.selectedEntries.count)}" class="file-action-button"><MoveIcon /></button>
                                {/if}
                            {/if}
                        </div>

                        <!-- Right buttons -->
                        <div class="h-full flex items-center gap-2">
                            {#if filesState.isFileListOpen}
                                <FileSortingButton></FileSortingButton>
                            
                                <FileViewTypeButton></FileViewTypeButton>
                            {/if}

                            {#if uiState.isDesktop && filesState.isFileListOpen}
                                <NewFileButton></NewFileButton>
                            {/if}

                            {#if textFileViewerState.isFileSavable}
                                <button on:click={() => { saveEditedFile() }} class="h-full flex items-center justify-center gap-2 bg-surface-button rounded-md px-4">
                                    <div class="h-[1.2rem]">
                                        <SaveIcon />
                                    </div>
                                    <p>Save</p>
                                </button>
                            {/if}

                            {#if uiState.isDesktop}
                                <FileDetailsButton></FileDetailsButton>
                            {/if}
                        </div>
                    </div>
                </div>
            </div>

            <!-- Files -->
            <div class="h-[calc(100%-3rem)] w-full mt-2 relative">
                {#if filesState.data.folderMeta || overrideTopLevelFolderUrlPath}
                    <div 
                        bind:this={filesState.scroll.container} 
                        class="h-full overflow-y-auto overflow-x-hidden custom-scrollbar lg:gutter-stable-both 
                            {filesState.data.fileMeta || filesState.metaLoading ? '' : ''}
                        ">
                        <FileBrowser />
                    </div>
                {/if}

                {#if filesState.data.fileMeta}
                    <div class="h-full absolute top-0 left-0 center" class:!hidden={filesState.metaLoading}>
                        <FileViewer />
                    </div>
                {/if}
                
                {#if filesState.metaLoading}
                    <div class="center">
                        <Loader></Loader>
                    </div>
                {:else if !filesState.data.currentMeta && !overrideTopLevelFolderUrlPath}
                    <div class="center">
                        <p class="text-xl">Failed to load this file.</p>
                    </div>
                {/if}
            </div>
        </div>


        <div class="contents">
            <!-- File info sidebar -->
            {#if filesState.ui.detailsOpen}
                <div on:click={filesState.unselect} class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex justify-end pointer-events-none lg:contents min-h-0">
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

{#if filesState.isFolderOpen}
    <FileDropzone on:filesDropped={event_filesDropped}></FileDropzone>
{/if}

<style lang="postcss">
    :global(.file-action-button) {
        @apply h-full aspect-square flex items-center justify-center rounded-lg p-2 bg-surface-button;
    }
</style>