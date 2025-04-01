<script lang="ts">
    import { afterNavigate, beforeNavigate, goto } from "$app/navigation";
    import { page } from "$app/state"
    import type { FileMetadata } from "$lib/code/auth/types";
    import { getFileData, streamFileContent, type FileData } from "$lib/code/module/files";
    import type { ulid } from "$lib/code/types";
    import { debounceFunction, filenameFromPath, formatBytes, formatUnixMillis, formatUnixTimestamp, isBlank, pageTitle, sortArray, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc } from "$lib/code/util/codeUtil.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import Popover from "$lib/component/Popover.svelte";
    import { untrack } from "svelte";

    const path = $derived.by(() => {
        const param = page.params.path
        if (isBlank(param)) return "/"
        return param
    })
    const urlPath = $derived(page.url.pathname)
    const segments = $derived(path.split("/"))
    const title = $derived(pageTitle(segments[segments.length - 1] || "Files"))

    let loading = $state(true)
    let loadingContent = $state(true)
    let data = $state(null) as FileData | null
    let fileContent: Blob | null = $state(null)
    let abortController: AbortController = $state(new AbortController())

    // Folder entries
    let sortedEntries = $derived.by(() => {
        if (!data || !data.entries) return null
        const files = data.entries.filter((v) => v.fileType === "FILE" || v.fileType === "FILE_LINK")
        const folders = data.entries.filter((v) => v.fileType === "FOLDER" || v.fileType === "FOLDER_LINK")
        
        const sortedFiles = sortArrayByNumberDesc(files, f => f.modifiedDate)
        const sortedFolders = sortArrayByNumberDesc(folders, f => f.modifiedDate)

        return [...sortedFolders, ...sortedFiles]
    })
    let selectedEntry: ulid | null = $state(null)

    // Scrolling
    let scrollContainer: HTMLElement | null = $state(null)
    let pathScrollPositions: Record<string, number> = $state({})

    // Entry menu popup
    let entryMenuButton: HTMLButtonElement | null = $state(null)
    let menuEntry: FileMetadata | null = $state(null)

    $effect(() => {
        if (path) {
            untrack(() => {
                if (abortController) {
                    abortController.abort()
                }
                abortController = new AbortController()

                // untrck(() => {
                    data = null
                    selectedEntry = null
                    entryMenuButton = null
                    menuEntry = null
                    fileContent = null
                // })

                if (path === "/") {
                    pathScrollPositions = {}
                }

                loadPageData(path).then(() => {
                    recoverScrollPosition()
                })
            })
        }
    })

    beforeNavigate(() => {
        saveScrollPosition()
    })

    let lastFetchedPath: string | null = $state(null)
    async function loadPageData(filePath: string) {
        lastFetchedPath = filePath
        loading = true

        const result = await getFileData(filePath, abortController?.signal)
        if (lastFetchedPath !== filePath) return
        
        if (result) {
            data = result
            if (data.meta.fileType.startsWith("FILE")) {
                loadingContent = true
                await loadFileContent(filePath)
                loadingContent = false
            }
        }
        loading = false
    }

    async function loadFileContent(filePath: string) {
        const blob = await streamFileContent(filePath, abortController.signal)
        if (lastFetchedPath !== filePath) return
        if (!blob) return
        fileContent = blob
    }

    /**
     * onClick for file entry
     */
    function entryOnClick(entry: FileMetadata) {
        if (selectedEntry === entry.filename) {
            goto(`${urlPath}/${filenameFromPath(entry.filename)}`)
        } else {
            selectedEntry = entry.filename
        }
    }

    /**
     * onClick for entry list container
     */
    function containerOnClick() {
        selectedEntry = null
    }

    /**
     * onClick for entry menu
     */
    function entryMenuOnClick(button: HTMLButtonElement, entry: FileMetadata) {
        entryMenuButton = button
        menuEntry = entry
    }
    function entryMenuOnClose() {
        entryMenuButton = null
        menuEntry = null
    }

    // Scrolling position
    function saveScrollPosition() {
        if (!path || !scrollContainer) return
        const pos = scrollContainer.scrollTop
        pathScrollPositions[path] = pos
    }

    function recoverScrollPosition() {
        if (!path || !scrollContainer) return
        const pos = pathScrollPositions[path]
        if (!pos) return
        scrollContainer.scrollTo({top: pos})
    }

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
        <div on:click={containerOnClick} class="w-full flex h-full">
            <div  bind:this={scrollContainer} class="w-full lg:w-[calc(100%-20rem)] flex flex-col h-full overflow-y-auto custom-scrollbar scrollbar-padding">
                <div class="w-full h-[3rem] shrink-0 flex px-6 items-center justify-evenly">
                    <p>Header majg</p>
                </div>

                <div class="h-[calc(100%-3rem)] w-full">
                    {#if !loading && data}
                        {#if data.meta.fileType === "FOLDER" && sortedEntries}
                            <table on:click|stopPropagation class="w-full h-fit">
                                <thead>
                                    <tr class="text-neutral-700 dark:text-neutral-400">
                                        <th class="font-medium text-left px-4 py-2 w-auto min-w-[50%]">Name</th>
                                        <th class="font-medium text-right px-4 py-2 whitespace-nowrap">Last Modified</th>
                                        <th class="font-medium text-right px-4 py-2 whitespace-nowrap">Size</th>
                                        <th class="font-medium text-center px-4 py-2 w-12"></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {#each sortedEntries as entry}
                                        {@const selected = selectedEntry === entry.filename}
                                        <tr 
                                            on:click={()=>{ entryOnClick(entry) }} 
                                            class="!h-[2.5rem] !max-h-[2.5rem] cursor-pointer px-2 {selected ? 'bg-blue-200 dark:bg-sky-950 select-none' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'}"
                                        >
                                            <td class="h-full px-4 py-0 align-middle">
                                                <div class="flex items-center gap-2">
                                                    <div class="h-6 aspect-square fill-neutral-500 flex-shrink-0 flex items-center justify-center">
                                                        {#if entry.fileType.startsWith("FILE")}
                                                            <FileIcon></FileIcon>
                                                        {:else if entry.fileType.startsWith("FOLDER")}
                                                            <FolderIcon></FolderIcon>
                                                        {/if}
                                                    </div>
                                                    <p class="truncate">{filenameFromPath(entry.filename)}</p>
                                                </div>
                                            </td>
                                            <td class="h-full px-4 py-0 opacity-70 whitespace-nowrap align-middle text-right">{formatUnixMillis(entry.modifiedDate)}</td>
                                            <td class="h-full px-4 py-0 whitespace-nowrap align-middle text-right">{formatBytes(entry.size)}</td>
                                            <td class="h-full text-center align-middle w-12 p-1">
                                                <button 
                                                    on:click|stopPropagation={(e) => { entryMenuOnClick(e.currentTarget, entry) }} 
                                                    class="items-center h-full aspect-square justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 "
                                                >
                                                    <ThreeDotsIcon></ThreeDotsIcon>
                                                </button>
                                            </td>
                                        </tr>
                                    {/each}
                                </tbody>
                            </table>
                        {:else if data.meta.fileType.startsWith("FILE")}
                            {#if fileContent}
                                <div></div>
                            {:else if loadingContent}
                                <div class="center">
                                    <Loader />
                                </div>
                            {:else}
                                <div class="center">
                                    <p class="text-lg">Failed to open file</p>
                                </div>
                            {/if}                            
                        {/if}
                    {:else if !loading && !data}
                        <div class="center">
                            <p class="text-xl">Failed to load this file.</p>
                        </div>
                    {:else}
                        <div class="center">
                            <Loader></Loader>
                        </div>
                    {/if}
                </div>

                {#if entryMenuButton && menuEntry}
                    <Popover button={entryMenuButton} isOpen={true} marginRem={0.1} onClose={entryMenuOnClose}>
                        <div class="rounded bg-neutral-300 dark:bg-neutral-800 py-2 flex flex-col max-w-full w-[18rem]">
                            <button>Permissions</button>
                        </div>
                    </Popover>
                {/if}
            </div>

            <!-- File info sidebar -->
            <div class="hidden lg:flex flex-col h-full w-[20rem] xpy-4">
                <div class="size-full overflow-auto xrounded-xl bg-neutral-200 dark:bg-neutral-850">

                </div>
            </div>
        </div>
</div>