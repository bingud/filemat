<script lang="ts">
    import { beforeNavigate, goto } from "$app/navigation";
    import { getFileData, streamFileContent, type FileData } from "$lib/code/module/files";
    import { getFileExtension, pageTitle, run } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onDestroy, untrack } from "svelte";
    import FileViewer from "./code/FileViewer.svelte";
    import { fileCategories, isFileCategory } from "$lib/code/data/files";
    import { createFilesState, destroyFilesState, filesState } from "./code/filesState.svelte";
    import { createBreadcrumbState, destroyBreadcrumbState, type Segment } from "./code/breadcrumbState.svelte";
    import Breadcrumbs from "./code/Breadcrumbs.svelte";
    import FileBrowser from "./code/FileBrowser.svelte";
    import { loadFileContent } from "./code/util/files";
    
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

    

    /**
     * onClick for entry list container
     */
    function containerOnClick() {
        filesState.selectedEntry = null
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
    <div on:click={containerOnClick} class="w-full flex h-full">
        <div bind:this={filesState.scroll.container} class="w-full lg:w-[calc(100%-20rem)] flex flex-col h-full overflow-y-auto custom-scrollbar scrollbar-padding">
            <!-- Header -->
            <div class="w-full h-[3rem] shrink-0 flex px-2 items-center justify-between">
                <Breadcrumbs></Breadcrumbs>
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

        <!-- File info sidebar -->
        <div class="hidden lg:flex flex-col h-full w-[20rem] xpy-4">
            <div class="size-full overflow-auto xrounded-xl bg-neutral-200 dark:bg-neutral-850">

            </div>
        </div>
    </div>
</div>