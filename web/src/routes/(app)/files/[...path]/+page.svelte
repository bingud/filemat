<script lang="ts">
    import { afterNavigate, beforeNavigate, goto } from "$app/navigation";
    import { page } from "$app/state"
    import type { FileMetadata } from "$lib/code/auth/types";
    import { getFileData, streamFileContent, type FileData } from "$lib/code/module/files";
    import type { ulid } from "$lib/code/types";
    import { debounceFunction, filenameFromPath, forEachReversed, formatBytes, formatUnixMillis, formatUnixTimestamp, getFileExtension, isBlank, pageTitle, sortArray, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc } from "$lib/code/util/codeUtil.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { untrack } from "svelte";
    import FileViewer from "./FileViewer.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";
    //@ts-ignore
    import { Popover } from "bits-ui";
    import { dev } from "$app/environment";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { calculateTextWidth, remToPx } from "$lib/code/util/uiUtil";
    import SettingsSidebar from "../../settings/components/SettingsSidebar.svelte";
    import { PopoverTrigger } from "$lib/components/ui/popover";
    
    const path = $derived.by(() => {
        const param = page.params.path
        if (isBlank(param)) return "/"
        return param
    })
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
    let entryMenuPopoverOpen = $state(dev)

    // Breadcrumbs
    let breadcrumbContainerWidth: number = $state(0)
    type Segment = { name: string, path: string, width: number }
    let breadcrumbs = $derived.by(() => {
        uiState.screenWidth

        const chevronWidth = remToPx(1)
        const paddingWidth = remToPx(1)
        const totalAdditionalWidth = chevronWidth + paddingWidth

        const fullSegments = segments.map((seg, index) => {
            const width = calculateTextWidth(seg)
            const fullPath = segments.slice(0, index + 1).join("/")
            return { name: seg, path: fullPath, width: width }
        })

        const visible: Segment[] = []
        const hidden: Segment[] = []

        // width of visible breadcrumbs
        const hiddenSegmentsButton = calculateTextWidth('...') + paddingWidth
        let width = 0 + hiddenSegmentsButton
        let outOfSpace = false

        // calculate which breadcrumbs will be visible
        forEachReversed(fullSegments, (seg, index) => {
            if (outOfSpace) {
                hidden.push(seg)
                return
            }

            const segmentWidth = index === 0 ? seg.width : seg.width + totalAdditionalWidth
            width += segmentWidth
            if (width > breadcrumbContainerWidth) {
                hidden.push(seg)
                outOfSpace = true
            } else {
                visible.push(seg)
            }
        })

        return { hidden: hidden, visible: visible.reverse() }
    })

    $effect(() => {
        if (path) {
            untrack(() => {
                if (abortController) {
                    abortController.abort()
                }
                abortController = new AbortController()

                data = null
                selectedEntry = null
                entryMenuButton = null
                menuEntry = null
                fileContent = null

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
            openEntry(entry.filename)
        } else {
            selectedEntry = entry.filename
        }
    }

    function openEntry(path: string) {
        goto(`/files${path}`)
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
        entryMenuPopoverOpen = true
    }
    function entryMenuPopoverOnOpenChange(open: boolean) {
        if (!open) {
            entryMenuButton = null
            menuEntry = null
        }
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
        <div bind:this={scrollContainer} class="w-full lg:w-[calc(100%-20rem)] flex flex-col h-full overflow-y-auto custom-scrollbar scrollbar-padding">
            <!-- Header -->
            <div class="w-full h-[3rem] shrink-0 flex px-2 items-center justify-between">
                <!-- Breadcrumbs -->
                <div bind:offsetWidth={breadcrumbContainerWidth} class="flex items-center h-[2rem] w-[85%]">
                    {#if path === "/"}
                        <p class="px-2 py-1">Files</p>
                    {:else}
                        {@const hiddenEmpty = breadcrumbs.hidden.length < 1}
                        <!-- Change chevron width in breadcrumb calculator -->

                        {#snippet breadcrumbButton(segment: Segment, className: string)}
                            <button disabled={path === segment.path} title={segment.name} on:click={() => { openEntry(`/${segment.path}`) }} class="py-1 px-2 {className}">{segment.name}</button>
                        {/snippet}

                        {#if !hiddenEmpty}
                            <Popover.Root>
                                <PopoverTrigger>
                                    <button class="rounded py-1 px-2 hover:bg-neutral-300 dark:hover:bg-neutral-800">...</button>
                                </PopoverTrigger>
                                <Popover.Content align="start" sideOffset={8}>
                                    <div class="w-[20rem] max-w-screen rounded-lg bg-neutral-800 py-2">
                                        {#each breadcrumbs.hidden as segment}
                                            {@render breadcrumbButton(segment, "truncate w-full text-start hover:bg-neutral-700")}
                                        {/each}
                                    </div>
                                </Popover.Content>
                            </Popover.Root>
                        {/if}
                        
                        {#each breadcrumbs.visible as segment, index}
                            <div class="flex items-center h-full">
                                {#if index !== 0 || !hiddenEmpty}
                                    <div class="h-full py-2 flex items-center justify-center">
                                        <ChevronRightIcon />
                                    </div>
                                {/if}
                                {@render breadcrumbButton(segment, "rounded hover:bg-neutral-300 dark:hover:bg-neutral-800")}
                            </div>
                        {/each}
                    {/if}
                </div>
            </div>

            <!-- Files -->
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
                                                class="items-center h-full aspect-square justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:hover:fill-neutral-500"
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
                            <div class="center">
                                <FileViewer blob={fileContent} filename={data.meta.filename} />
                            </div>
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
                {#key entryMenuButton || menuEntry}
                    <Popover.Root bind:open={entryMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
                        <Popover.Content onInteractOutside={() => { entryMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
                            {@render entryMenuPopover(menuEntry)}
                        </Popover.Content>
                    </Popover.Root>
                {/key}
            {/if}
        </div>

        <!-- File info sidebar -->
        <div class="hidden lg:flex flex-col h-full w-[20rem] xpy-4">
            <div class="size-full overflow-auto xrounded-xl bg-neutral-200 dark:bg-neutral-850">

            </div>
        </div>
    </div>
</div>


{#snippet entryMenuPopover(entry: FileMetadata)}
    <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-800 py-2 flex flex-col">
        <button class="popover-button">Permissions</button>
        <hr class="basic-hr my-2">
        <p class="px-4 truncate opacity-70">File: {filenameFromPath(entry.filename)}</p>
    </div>
{/snippet}

<style>
    @import "/src/app.css" reference;

    .popover-button {
        @apply py-1 px-4 text-start hover:bg-neutral-300 dark:hover:bg-neutral-700;
    }
</style>