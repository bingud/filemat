<script lang="ts">
    import type { FullFileMetadata } from "$lib/code/auth/types";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { parentFromPath } from "$lib/code/util/codeUtil.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import BookmarkIcon from "$lib/component/icons/BookmarkIcon.svelte";
    import BookmarkXIcon from "$lib/component/icons/BookmarkXIcon.svelte";
    import CheckboxIcon from "$lib/component/icons/CheckboxIcon.svelte";
    import CopyIcon from "$lib/component/icons/CopyIcon.svelte";
    import DownloadIcon from "$lib/component/icons/DownloadIcon.svelte";
    import EditIcon from "$lib/component/icons/EditIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import MinusIcon from "$lib/component/icons/MinusIcon.svelte";
    import MoveIcon from "$lib/component/icons/MoveIcon.svelte";
    import NewTabIcon from "$lib/component/icons/NewTabIcon.svelte";
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte";
    import { option_downloadSelectedFiles } from "../../_code/fileActions";
    import type { FileContextMenuProps } from "../../_code/fileBrowserUtil";

    let {
        entryMenuButton,
        entryMenuPopoverOnOpenChange,
        menuEntry,
        option_rename,
        option_move,
        option_copy,
        option_delete,
        option_details,
        option_save,
        closeFileContextMenuPopover,
    }: FileContextMenuProps & {
        entryMenuButton: HTMLElement,
        entryMenuPopoverOnOpenChange: (value: boolean) => void,
        menuEntry: FullFileMetadata,
    } = $props()

    function close() {
        filesState.ui.fileContextMenuPopoverOpen = false
    }

    function option_unselectAllFiles() {
        filesState.selectedEntries.setSelected([])
        closeFileContextMenuPopover()
    }
    function option_selectAllFiles() {
        if (filesState.isSearchOpen && filesState.search.entries) {
            filesState.selectedEntries.setSelected(filesState.search.entries.map(v => v.path))
        } else if (filesState.data.entries) {
            filesState.selectedEntries.setSelected(filesState.data.entries.map(v => v.path))
        }
        closeFileContextMenuPopover()
    }
</script>



<Popover.Root bind:open={filesState.ui.fileContextMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
    <Popover.Content onInteractOutside={() => { filesState.ui.fileContextMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-popover select-none">
            <a 
                href={`${filesState.meta.pagePath}${menuEntry.path}`}
                on:click={close}
                on:auxclick={close}
                target="_blank" class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2" rel="noopener noreferrer"
            >
                <div class="size-5 flex-shrink-0">
                    <NewTabIcon />
                </div>
                <span>Open in new tab</span>
            </a>

            {#if filesState.meta.type === "allShared" || filesState.isSearchOpen || filesState.meta.type === "saved"}
                <a 
                    href={`${filesState.meta.pagePath}${parentFromPath(menuEntry.path)}`}
                    on:click={close}
                    on:auxclick={close}
                    class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2" rel="noopener noreferrer"
                >
                    <div class="size-5 flex-shrink-0">
                        <FolderIcon />
                    </div>
                    <span>Open containing folder</span>
                </a>
            {/if}

            <button 
                on:click={(e) => { option_downloadSelectedFiles(e, [menuEntry.path]); closeFileContextMenuPopover() }} 
                class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2"
            >
                <div class="size-5 flex-shrink-0">
                    <DownloadIcon />
                </div>
                <span>Download</span>
            </button>

            {#if !filesState.isShared && menuEntry.permissions?.includes("RENAME")}
                <button on:click={() => { option_rename(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <EditIcon />
                    </div>
                    <span>Rename</span>
                </button>
            {/if}

            {#if !filesState.isShared && menuEntry.permissions?.includes("MOVE")}
                {#if filesState.meta.type === "files"}
                    <button on:click={() => { option_move(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                        <div class="size-5 flex-shrink-0">
                            <MoveIcon />
                        </div>
                        <span>Move</span>
                    </button>
                {/if}
            {/if}

            {#if !filesState.isShared}
                <button on:click={() => { option_copy(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <CopyIcon />
                    </div>
                    <span>Copy</span>
                </button>
            {/if}

            {#if !filesState.isShared && menuEntry.permissions?.includes("DELETE")}
                <button on:click={() => { option_delete(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <TrashIcon />
                    </div>
                    <span>Delete</span>
                </button>
            {/if}

            {#if !filesState.isShared && menuEntry.isSaved != null}
                <button on:click={() => { option_save(menuEntry!, menuEntry.isSaved ? "unsave" : "save") }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        {#if menuEntry.isSaved}
                            <BookmarkXIcon />
                        {:else}
                            <BookmarkIcon />
                        {/if}
                    </div>
                    <span>
                        {#if menuEntry.isSaved}
                            Unsave
                        {:else}
                            Save
                        {/if}
                    </span>
                </button>
            {/if}

            <button on:click={() => { option_details(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                <div class="size-5 flex-shrink-0">
                    <InfoIcon />
                </div>
                <span>Details</span>
            </button>

            <!-- Divider -->
            <hr class="basic-hr my-2">

            {#if filesState.selectedEntries.count !== filesState.data.entries?.length && filesState.selectedEntries.count !== filesState.search.entries?.length}
                <button on:click={() => { option_selectAllFiles() }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <CheckboxIcon />
                    </div>
                    <span>Select all</span>
                </button>
            {/if}

            {#if filesState.selectedEntries.count > 0}
                <button on:click={() => { option_unselectAllFiles() }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <MinusIcon />
                    </div>
                    <span>Unselect all</span>
                </button>
            {/if}

            <!-- Divider -->
            <hr class="basic-hr my-2">

            <p class="px-4 truncate opacity-70">{menuEntry.filename!}</p>
        </div>
    </Popover.Content>
</Popover.Root>