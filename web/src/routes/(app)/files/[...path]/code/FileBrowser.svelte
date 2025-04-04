<script lang="ts">
    import { dev } from "$app/environment";
    import { goto } from "$app/navigation";
    import type { FileMetadata } from "$lib/code/auth/types";
    import type { ulid } from "$lib/code/types";
    import { filenameFromPath, formatBytes, formatUnixMillis } from "$lib/code/util/codeUtil.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import { filesState } from "./filesState.svelte";

    // Entry menu popup
    let entryMenuButton: HTMLButtonElement | null = $state(null)
    let menuEntry: FileMetadata | null = $state(null)
    let entryMenuPopoverOpen = $state(dev)

    /**
     * onClick for file entry
     */
    function entryOnClick(entry: FileMetadata) {
        if (filesState.selectedEntry === entry.filename) {
            openEntry(entry.filename)
        } else {
            filesState.selectedEntry = entry.filename
        }
    }

    /**
     * onClick for entry menu
     */
    function entryMenuOnClick(button: HTMLButtonElement, entry: FileMetadata) {
        entryMenuButton = button
        menuEntry = entry
        entryMenuPopoverOpen = true
    }

    function openEntry(path: string) {
        goto(`/files${path}`)
    }
    
    function entryMenuPopoverOnOpenChange(open: boolean) {
        if (!open) {
            entryMenuButton = null
            menuEntry = null
        }
    }
</script>


{#if filesState.data.sortedEntries}
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
            {#each filesState.data.sortedEntries as entry}
                {@const selected = filesState.selectedEntry === entry.filename}
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


    {#if entryMenuButton && menuEntry}
        {#key entryMenuButton || menuEntry}
            <Popover.Root bind:open={entryMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
                <Popover.Content onInteractOutside={() => { entryMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
                    <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col">
                        <button class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700">Permissions</button>
                        <hr class="basic-hr my-2">
                        <p class="px-4 truncate opacity-70">File: {filenameFromPath(menuEntry.filename)}</p>
                    </div>
                </Popover.Content>
            </Popover.Root>
        {/key}
    {/if}
{:else}
    <div class="center">
        <p>No folder is open.</p>
    </div>
{/if}