<script>
  import { filesState } from "$lib/code/stateObjects/filesState.svelte";
  import { Popover } from "$lib/component/bits-ui-wrapper";
  import FileIcon from "$lib/component/icons/FileIcon.svelte";
  import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
  import PlusIcon from "$lib/component/icons/PlusIcon.svelte";
  import { handleNewFolder } from "../../_code/fileActions";
  import { handleNewFile, handleUpload } from "../../_code/pageLogic";


</script>


<Popover.Root bind:open={filesState.ui.newFilePopoverOpen}>
    <Popover.Trigger title="Create or upload a file or folder." class="h-full flex items-center justify-center" hidden={filesState.data.meta?.fileType.startsWith("FILE")}>
        <div class="h-full flex items-center justify-center gap-2 bg-surface-button rounded-md px-4">
            <div class="h-[1.2rem]">
                <PlusIcon></PlusIcon>
            </div>
            <p>New</p>
        </div>
    </Popover.Trigger>
    <Popover.Content align="end" class="relative z-50">
        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50">
            <button on:click={handleUpload} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                <div class="size-5 flex-shrink-0">
                    <PlusIcon />
                </div>
                <span>Upload</span>
            </button>
            <button on:click={handleNewFolder} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                <div class="size-5 flex-shrink-0">
                    <FolderIcon />
                </div>
                <span>Folder</span>
            </button>
            <button on:click={handleNewFile} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                <div class="size-5 flex-shrink-0">
                    <FileIcon />
                </div>
                <span>File</span>
            </button>
        </div>
    </Popover.Content>
</Popover.Root>