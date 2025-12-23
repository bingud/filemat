<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { fileSortingModes, type FileSortingMode, type SortingDirection } from "$lib/code/types/fileTypes";
    import { entriesOf } from "$lib/code/util/codeUtil.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import CheckmarkIcon from "$lib/component/icons/CheckmarkIcon.svelte";
    import SortAscendingIcon from "$lib/component/icons/SortAscendingIcon.svelte";
    import SortDescendingIcon from "$lib/component/icons/SortDescendingIcon.svelte";

    let mode = $derived(filesState.sortingMode)
    let direction = $derived(filesState.sortingDirection)

    function setMode(newMode: FileSortingMode) {
        filesState.setFileSortingMode(newMode)
    }
    function setDirection(newDirection: SortingDirection) {
        filesState.setFileSortingDirection(newDirection)
    }
    
</script>


<Popover.Root bind:open={filesState.ui.fileSortingMenuPopoverOpen}>
    <Popover.Trigger title="Change file sorting mode." class="h-full flex items-center justify-center">
        <div class="h-full flex items-center justify-center gap-2 bg-surface-content-button rounded-md px-4">
            <div class="h-[1.2rem]">
                {#if direction === "asc"}
                    <SortAscendingIcon></SortAscendingIcon>
                {:else}
                    <SortDescendingIcon></SortDescendingIcon>
                {/if}
            </div>
            <p class="capitalize">{mode}</p>
        </div>
    </Popover.Trigger>
    <Popover.Content align="end" class="relative z-50">
        <div class="w-[14rem] surface-popover-container">
            {#each entriesOf(fileSortingModes) as [modeId, modeName]}
                <button on:click={() => setMode(modeId)} class="surface-popover-button">
                    <div class="size-5 flex-shrink-0">
                        {#if mode === modeId}
                            <CheckmarkIcon />
                        {/if}
                    </div>
                    <span>{modeName}</span>
                </button>
            {/each}

            <hr class="basic-hr my-2">

            <button on:click={() => { setDirection("asc") }} class="surface-popover-button">
                <div class="size-5 flex-shrink-0">
                    {#if direction === "asc"}
                        <SortAscendingIcon />
                    {/if}
                </div>
                <span>
                    {#if mode === "name"}
                        A to Z
                    {:else if mode === "modified"}
                        Oldest first
                    {:else if mode === "size"}
                        Smallest first
                    {:else if mode === "created"}
                        Oldest first
                    {/if}
                </span>
            </button>
            <button on:click={() => { setDirection("desc") }} class="surface-popover-button">
                <div class="size-5 flex-shrink-0">
                    {#if direction === "desc"}
                        <SortDescendingIcon />
                    {/if}
                </div>
                <span>
                    {#if mode === "name"}
                        Z to A
                    {:else if mode === "modified"}
                        Newest first
                    {:else if mode === "size"}
                        Largest first
                    {:else if mode === "created"}
                        Newest first
                    {/if}
                </span>
            </button>
        </div>
    </Popover.Content>
</Popover.Root>