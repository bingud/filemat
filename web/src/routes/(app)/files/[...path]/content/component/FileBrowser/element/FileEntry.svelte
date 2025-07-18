<script lang="ts">
    import { type FullFileMetadata } from "$lib/code/auth/types";
    import { fileCategories } from "$lib/code/data/files";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { addSuffix, formatBytesRounded, formatUnixMillis, getFileExtension } from "$lib/code/util/codeUtil.svelte";
    import FileArrow from "$lib/component/icons/FileArrow.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderArrow from "$lib/component/icons/FolderArrow.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";

    let {
        entry,
        event_dragStart,
        event_dragOver,
        event_dragLeave,
        event_drop,
        event_dragEnd,
        entryOnClick,
        entryOnContextMenu,
        onClickSelectCheckbox,
        entryMenuOnClick,
    }: { 
        entry: FullFileMetadata,
        event_dragStart: (e: DragEvent, entry: FullFileMetadata) => void,
        event_dragOver: (e: DragEvent, entry: FullFileMetadata) => void,
        event_dragLeave: (e: DragEvent, entry: FullFileMetadata) => void,
        event_drop: (e: DragEvent, entry: FullFileMetadata) => void,
        event_dragEnd: (e: DragEvent, entry: FullFileMetadata) => void,
        entryOnClick: (e: MouseEvent, entry: FullFileMetadata) => void,
        entryOnContextMenu: (e: MouseEvent, entry: FullFileMetadata) => void,
        onClickSelectCheckbox: (path: string) => void,
        entryMenuOnClick: (button: HTMLButtonElement, entry: FullFileMetadata) => void,
    } = $props()
    
    let isSelected = $derived(filesState.selectedEntries.list.includes(entry.path))
</script>


<a
    on:click|preventDefault={(e) => entryOnClick(e, entry)}
    on:contextmenu={(e) => { entryOnContextMenu(e, entry) }}
    draggable={entry.permissions.includes("MOVE")}
    data-entry-path={entry.path} rel="noopener noreferrer"
    class="
        file-grid h-[2.5rem] gap-x-2 items-center cursor-pointer select-none group 
        {isSelected ? 'bg-blue-200 dark:bg-sky-950' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'}
    "
    href={
        (entry.fileType === "FILE" || 
        (entry.fileType === "FILE_LINK" && appState.followSymlinks))
            ? addSuffix(filesState.data.contentUrl, "/") + `${entry.filename!}`
            : `/files${entry.path}`
    }
    on:dragstart={(e) => { event_dragStart(e, entry) }}
    on:dragover={(e) => { event_dragOver(e, entry) }}
    on:dragleave={(e) => { event_dragLeave(e, entry) }}
    on:drop={(e) => { event_drop(e, entry) }}
    on:dragend={(e) => { event_dragEnd(e, entry) }}
>
    <!-- Filename + Icon -->
    <div class="h-full flex items-center overflow-hidden">
        {#key isSelected}
            <div on:click|stopPropagation|preventDefault={() => { onClickSelectCheckbox(entry.path) }} class="h-full flex items-center justify-center pl-2 pr-1">
                <input checked={isSelected} class="opacity-0 checked:opacity-100 group-hover:opacity-100" type="checkbox">
            </div>
        {/key}

        <div class="h-full flex items-center gap-2 min-w-0 overflow-hidden whitespace-nowrap text-ellipsis">
            <div class="h-6 aspect-square fill-neutral-500 stroke-neutral-500 flex-shrink-0 flex items-center justify-center py-[0.1rem]">
                {#key entry.filename}
                    {@const format = fileCategories[getFileExtension(entry.filename!)]}

                    {#if format === "image"}
                        <img loading="lazy" alt="" src="/api/v1/file/image-thumbnail?size=48&path={entry.path}" class="size-full w-auto">
                    {:else if format === "video"}
                        <img loading="lazy" alt="" src="/api/v1/file/video-preview?size=48&path={entry.path}" class="size-full w-auto">
                    {:else}
                        {#if entry.fileType === "FILE"}
                            <FileIcon />
                        {:else if entry.fileType === "FILE_LINK"}
                            <FileArrow />
                        {:else if entry.fileType === "FOLDER"}
                            <FolderIcon />
                        {:else if entry.fileType === "FOLDER_LINK"}
                            <FolderArrow />
                        {/if}
                    {/if}
                {/key}
            </div>
            <p class="truncate py-1">
                {entry.filename!}
            </p>
        </div>
    </div>

    <!-- Last Modified -->
    <div class="h-full text-right whitespace-nowrap max-sm:hidden flex items-center justify-end opacity-70 py-1">
        {formatUnixMillis(entry.modifiedDate)}
    </div>

    <!-- Size -->
    <div class="h-full text-right whitespace-nowrap max-md:hidden flex items-center justify-end py-1">
        {formatBytesRounded(entry.size)}
    </div>

    <!-- Menu button -->
    <div class="h-full text-center py-1 pr-1">
        <button
            on:click|stopPropagation|preventDefault={(e) => { entryMenuOnClick(e.currentTarget, entry) }}
            class="h-full aspect-square flex items-center justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:fill-neutral-500"
        >
            <ThreeDotsIcon />
        </button>
    </div>
</a>