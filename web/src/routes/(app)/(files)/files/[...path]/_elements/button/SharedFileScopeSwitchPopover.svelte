<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import CheckmarkIcon from "$lib/component/icons/CheckmarkIcon.svelte";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import { sharedFilesPageState } from "../../../../shared-files/state.svelte";

    function set(mode: boolean) {
        sharedFilesPageState.showAll = mode
    }
    
</script>


<Popover.Root bind:open={sharedFilesPageState.scopePopoverOpen}>
    <Popover.Trigger title="Toggle whether to show all shared files." class="h-full flex items-center justify-center">
        <div class="h-full flex items-center justify-center gap-2 bg-surface-content-button rounded-md px-4">
            <div class="h-[1.2rem]">
                {#if sharedFilesPageState.showAll}
                    <CheckmarkIcon></CheckmarkIcon>
                {:else}
                    <CloseIcon></CloseIcon>
                {/if}
            </div>
            <p class="capitalize">Show all</p>
        </div>
    </Popover.Trigger>
    <Popover.Content align="end" class="relative z-50">
        <div class="w-[14rem] surface-popover-container">
            <button on:click={() => set(false)} class="surface-popover-button">
                <div class="size-5 flex-shrink-0">
                    {#if sharedFilesPageState.showAll === false}
                        {#if filesState.metaLoading}
                            ...
                        {:else}
                            <CheckmarkIcon />
                        {/if}
                    {/if}
                </div>
                <span>Only shared by me</span>
            </button>

            <button on:click={() => set(true)} class="surface-popover-button">
                <div class="size-5 flex-shrink-0">
                    {#if sharedFilesPageState.showAll === true}
                        {#if filesState.metaLoading}
                            ...
                        {:else}
                            <CheckmarkIcon />
                        {/if}
                    {/if}
                </div>
                <span>All shared files</span>
            </button>
        </div>
    </Popover.Content>
</Popover.Root>