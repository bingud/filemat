<script lang="ts">
    import { goto } from "$app/navigation";
    import { page } from "$app/state"
    import type { FileMetadata } from "$lib/code/auth/types";
    import { getFileData, type FileData } from "$lib/code/module/files";
    import type { ulid } from "$lib/code/types";
    import { filenameFromPath, formatBytes, formatUnixMillis, formatUnixTimestamp, isBlank, pageTitle, sortArray, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc } from "$lib/code/util/codeUtil.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import Popover from "$lib/component/Popover.svelte";
    import { onMount } from "svelte"

    const path = $derived.by(() => {
        const param = page.params.path
        if (isBlank(param)) return "/"
        return param
    })
    const urlPath = $derived(page.url.pathname)
    const segments = $derived(path.split("/"))
    const title = $derived(pageTitle(segments[segments.length - 1] || "Files"))

    let loading = $state(true)
    let data = $state(null) as FileData | null
    let sortedEntries = $derived.by(() => {
        if (!data || !data.entries) return null
        const files = data.entries.filter((v) => v.fileType === "FILE" || v.fileType === "FILE_LINK")
        const folders = data.entries.filter((v) => v.fileType === "FOLDER" || v.fileType === "FOLDER_LINK")
        
        const sortedFiles = sortArrayByNumberDesc(files, f => f.modifiedDate)
        const sortedFolders = sortArrayByNumberDesc(folders, f => f.modifiedDate)

        return [...sortedFolders, ...sortedFiles]
    })
    let selectedEntry: ulid | null = $state(null)

    // Entry menu popup
    let entryMenuButton: HTMLButtonElement | null = $state(null)
    let menuEntry: FileMetadata | null = $state(null)

    $effect(() => {
        if (path) {
            console.log(path)

            data = null
            selectedEntry = null
            entryMenuButton = null
            menuEntry = null

            loadPageData(path)
        }
    })

    let loadingData: string | null = $state(null)
    async function loadPageData(filePath: string) {
        loadingData = filePath
        loading = true

        const result = await getFileData(filePath)
        if (loadingData !== filePath) return
        
        if (result) {
            data = result
        }
        loading = false
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

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
    {#if !loading && data}
        <div on:click={containerOnClick} class="w-full flex h-full">
            <div class="w-[calc(100%-20rem)] flex flex-col h-full overflow-y-auto custom-scrollbar">
                <div class="w-full h-[3rem] shrink-0 flex px-6 items-center justify-evenly">
                    <p>Header majg</p>
                </div>

                <div class="max-h-[calc(100%-3rem)] w-full px-2">
                    {#if data.meta.fileType === "FOLDER" && sortedEntries}
                        <table on:click|stopPropagation class="w-full h-full">
                            <thead>
                                <tr class="">
                                    <th class="text-left px-4 py-2 w-auto min-w-[50%]">Name</th>
                                    <th class="text-right px-4 py-2 whitespace-nowrap">Last Modified</th>
                                    <th class="text-right px-4 py-2 whitespace-nowrap">Size</th>
                                    <th class="text-center px-4 py-2 w-10"></th>
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
                                                <p class="truncate">{entry.filename}</p>
                                            </div>
                                        </td>
                                        <td class="h-full px-4 py-0 opacity-70 whitespace-nowrap align-middle text-right">{formatUnixMillis(entry.modifiedDate)}</td>
                                        <td class="h-full px-4 py-0 whitespace-nowrap align-middle text-right">{formatBytes(entry.size)}</td>
                                        <td class="h-full py-0 text-center align-middle w-10">
                                            <button 
                                                on:click|stopPropagation={(e) => { entryMenuOnClick(e.currentTarget, entry) }} 
                                                class="items-center h-full aspect-square justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50"
                                            >
                                                <ThreeDotsIcon></ThreeDotsIcon>
                                            </button>
                                        </td>
                                    </tr>
                                {/each}
                            </tbody>
                        </table>
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
            <div class="flex flex-col h-full w-[20rem] py-4">
                <div class="size-full overflow-auto rounded-xl bg-neutral-200 dark:bg-neutral-850">

                </div>
            </div>
        </div>
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