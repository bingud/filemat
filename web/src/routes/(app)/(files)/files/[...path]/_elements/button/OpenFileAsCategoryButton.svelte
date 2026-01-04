<script lang="ts">
    import type { FileCategory } from "$lib/code/data/files";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import ChevronDownIcon from "$lib/component/icons/ChevronDownIcon.svelte";

    let {
        location
    }: {
        location: "file-viewer" | "bar"
    } = $props()

    let isOpen = $state(false)
    let isLocationBar = $derived(location === "bar")

    function openAsFileType(type: FileCategory) {
        filesState.currentFile.displayedFileCategory = type
        isOpen = false
    }

</script>


<div class="z-10 {isLocationBar ? 'h-full' : ''}">
    <Popover.Root bind:open={isOpen}>
        <Popover.Trigger>
            {#snippet child({props})}
                <button {...props} class="
                    {isLocationBar 
                        ? 'h-full flex items-center justify-center gap-2 bg-surface-content-button rounded-md px-4'
                        : 'basic-button flex items-center gap-2'
                    }
                ">
                    <p>Open as</p>
                    <span class="h-4 block"><ChevronDownIcon /></span>
                </button>
            {/snippet}
        </Popover.Trigger>
        <Popover.Content align="start" sideOffset={8}>
            <div class="rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col w-[10rem]">
                <button on:click={() => { openAsFileType("text") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Text</button>
                <button on:click={() => { openAsFileType("image") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Image</button>
                <button on:click={() => { openAsFileType("video") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Video</button>
                <button on:click={() => { openAsFileType("audio") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Audio</button>
            </div>
        </Popover.Content>
    </Popover.Root>
</div>