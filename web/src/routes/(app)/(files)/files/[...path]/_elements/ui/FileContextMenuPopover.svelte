<script lang="ts">
    import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { parentFromPath } from "$lib/code/util/codeUtil.svelte";
    import { getContentUrl } from "$lib/code/util/stateUtils";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import BookmarkIcon from "$lib/component/icons/BookmarkIcon.svelte";
    import BookmarkXIcon from "$lib/component/icons/BookmarkXIcon.svelte";
    import DownloadIcon from "$lib/component/icons/DownloadIcon.svelte";
    import EditIcon from "$lib/component/icons/EditIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import MoveIcon from "$lib/component/icons/MoveIcon.svelte";
    import NewTabIcon from "$lib/component/icons/NewTabIcon.svelte";
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte";

    let {
        entryMenuButton,
        entryMenuPopoverOnOpenChange,
        menuEntry,
        option_rename,
        option_move,
        option_delete,
        option_details,
        option_save,
    }: {
        entryMenuButton: HTMLElement,
        entryMenuPopoverOnOpenChange: (value: boolean) => void,
        menuEntry: FullFileMetadata,
        option_rename: (entry: FileMetadata) => any,
        option_move: (entry: FileMetadata) => any,
        option_delete: (entry: FileMetadata) => any,
        option_details: (entry: FileMetadata) => any,
        option_save: (entry: FileMetadata, action: "save" | "unsave") => any,
    } = $props()

    function close() {
        filesState.ui.fileContextMenuPopoverOpen = false
    }
</script>



<Popover.Root bind:open={filesState.ui.fileContextMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
    <Popover.Content onInteractOutside={() => { filesState.ui.fileContextMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50 select-none">
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
                    target="_blank" class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2" rel="noopener noreferrer"
                >
                    <div class="size-5 flex-shrink-0">
                        <FolderIcon />
                    </div>
                    <span>Open containing folder</span>
                </a>
            {/if}

            <a 
                download 
                href={getContentUrl(menuEntry.path, false)} 
                target="_blank" 
                class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2"
                on:click={close}
                on:auxclick={close}
            >
                <div class="size-5 flex-shrink-0">
                    <DownloadIcon />
                </div>
                <span>Download</span>
            </a>

            {#if menuEntry.permissions.includes("MOVE")}
                <button on:click={() => { option_rename(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <EditIcon />
                    </div>
                    <span>Rename</span>
                </button>

                <button on:click={() => { option_move(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <MoveIcon />
                    </div>
                    <span>Move</span>
                </button>
            {/if}

            {#if menuEntry.permissions.includes("DELETE")}
                <button on:click={() => { option_delete(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                    <div class="size-5 flex-shrink-0">
                        <TrashIcon />
                    </div>
                    <span>Delete</span>
                </button>
            {/if}

            {#if menuEntry.isSaved != null && filesState.meta.type !== "shared"}
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

            <hr class="basic-hr my-2">
            <p class="px-4 truncate opacity-70">File: {menuEntry.filename!}</p>
        </div>
    </Popover.Content>
</Popover.Root>