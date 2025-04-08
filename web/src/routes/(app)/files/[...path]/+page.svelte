<script lang="ts">
    import { beforeNavigate } from "$app/navigation";
    import { getFileData } from "$lib/code/module/files";
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onDestroy, untrack } from "svelte";
    import FileViewer from "./content/FileViewer.svelte";
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "./content/code/breadcrumbState.svelte";
    import Breadcrumbs from "./content/Breadcrumbs.svelte";
    import FileBrowser from "./content/FileBrowser.svelte";
    import DetailsSidebar from "./content/DetailsSidebar.svelte";
    import { createFilesState, destroyFilesState, filesState } from "./content/code/filesState.svelte";
    import { fade, fly } from "svelte/transition";
    import { linear } from "svelte/easing";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    
    createFilesState()
    createBreadcrumbState()

    onDestroy(() => {
        destroyFilesState()
        destroyBreadcrumbState()
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || "Files"))

    $effect(() => {
        if (filesState.path) {
            untrack(() => {
                filesState.abort()
                filesState.clearState()

                filesState.data.content = null

                if (filesState.path === "/") {
                    filesState.scroll.pathPositions = {}
                }

                loadPageData(filesState.path).then(() => {
                    recoverScrollPosition()
                })
            })
        }
    })

    beforeNavigate(() => {
        saveScrollPosition()
    })

    async function loadPageData(filePath: string) {
        filesState.lastFilePathLoaded = filePath
        filesState.metaLoading = true

        const result = await getFileData(filePath, filesState.abortController?.signal)
        if (filesState.lastFilePathLoaded !== filePath) return
        
        if (result) {
            filesState.data.meta = result.meta
            filesState.data.entries = result.entries || null
        }
        filesState.metaLoading = false
    }

    // Scrolling position
    function saveScrollPosition() {
        if (!filesState.path || !filesState.scroll.container) return
        const pos = filesState.scroll.container.scrollTop
        filesState.scroll.pathPositions[filesState.path] = pos
    }

    function recoverScrollPosition() {
        if (!filesState.path || !filesState.scroll.container) return
        const pos = filesState.scroll.pathPositions[filesState.path]
        if (!pos) return
        filesState.scroll.container.scrollTo({top: pos})
    }

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
    <div class="w-full flex h-full min-h-0">
        <div bind:this={filesState.scroll.container} class="w-full {filesState.ui.detailsOpen ? 'w-[calc(100%-20rem)]' : 'w-full'} lg:w-full flex flex-col h-full overflow-y-auto overflow-x-hidden custom-scrollbar md:gutter-stable-both">
            <!-- Header -->
            <div class="w-full h-[3rem] shrink-0 flex px-2 items-center justify-between">
                <div bind:offsetWidth={breadcrumbState.containerWidth} class="w-[85%] h-full flex items-center">
                    <Breadcrumbs></Breadcrumbs>                    
                </div>

                <div class="w-[15%] h-full flex items-center justify-end">
                    <button on:click={() => { filesState.ui.toggleSidebar() }} class="h-full aspect-square p-3 group text-sm flex">
                        <p class="size-full rounded-full center ring ring-neutral-600 group-hover:bg-neutral-300 dark:group-hover:bg-neutral-700">i</p>
                    </button>
                </div>
            </div>

            <!-- Files -->
            <div class="h-[calc(100%-3rem)] w-full">
                {#if !filesState.metaLoading && filesState.data.meta}
                    {#if filesState.data.meta.fileType === "FOLDER" && filesState.data.sortedEntries}
                        <FileBrowser />
                    {:else if filesState.data.meta.fileType.startsWith("FILE")}
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
                <div on:click={filesState.unselect} class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex justify-end pointer-events-none lg:contents min-h-0">
                    <div transition:fly={{ duration: 150, x: 400, opacity: 1 }} class="flex flex-col h-full max-w-full w-[20rem] shrink-0 pointer-events-auto min-h-0">
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