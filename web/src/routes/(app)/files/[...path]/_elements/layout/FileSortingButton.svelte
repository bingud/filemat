<script>
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte";
    import SortAscendingIcon from "$lib/component/icons/SortAscendingIcon.svelte";
    import SortDescendingIcon from "$lib/component/icons/SortDescendingIcon.svelte";
    import { handleUpload } from "../../_code/pageLogic";


</script>


<Popover.Root bind:open={filesState.ui.fileSortingMenuPopoverOpen}>
    <Popover.Trigger title="Create or upload a file or folder." class="h-full flex items-center justify-center">
        <div class="h-full flex items-center justify-center gap-2 bg-surface-button rounded-md px-4">
            <div class="h-[1.2rem]">
                {#if filesState.sortingDirection === "asc"}
                    <SortAscendingIcon></SortAscendingIcon>
                {:else}
                    <SortDescendingIcon></SortDescendingIcon>
                {/if}
            </div>
            <p class="capitalize">{filesState.sortingMode}</p>
        </div>
    </Popover.Trigger>
    <Popover.Content align="end" class="relative z-50">
        <div class="w-[14rem] surface-popover-container">
            <button on:click={handleUpload} class="surface-popover-button">
                <div class="size-5 flex-shrink-0">
                    <PlusIcon />
                </div>
                <span>Name</span>
            </button>
        </div>
    </Popover.Content>
</Popover.Root>