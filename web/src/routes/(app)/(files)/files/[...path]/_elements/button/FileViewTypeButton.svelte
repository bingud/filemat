<script lang="ts">
    import { previewSizeLabels, previewSizes, type previewSize } from "$lib/code/config/values";
    import { setPreferenceSetting } from "$lib/code/module/settings"
    import { appState } from "$lib/code/stateObjects/appState.svelte"
    import { filesState } from "$lib/code/stateObjects/filesState.svelte"
    import { setPreviewSize } from "$lib/code/util/stateUtils";
    import { Popover } from "$lib/component/bits-ui-wrapper"
    import CircleIcon from "$lib/component/icons/CircleIcon.svelte"
    import GridIcon from "$lib/component/icons/GridIcon.svelte"
    import RowsIcon from "$lib/component/icons/RowsIcon.svelte"

    function setView(view: "rows" | "grid") {
        filesState.ui.fileViewType = view
        setPreferenceSetting("file_view_type", view)
    }

    let currentSize = $derived.by(() => {
        const isRows = filesState.ui.fileViewType === "rows" 
        return isRows ? appState.settings.previewSize.rows : appState.settings.previewSize.grid
    })
</script>

{#if filesState}
    <Popover.Root>
        <Popover.Trigger
            title="Change file view"
            class="file-action-button"
        >
            {#if filesState.ui.fileViewType === "rows"}
                <RowsIcon />
            {:else}
                <GridIcon />
            {/if}
        </Popover.Trigger>
        <Popover.Content align="end" class="relative z-50">
            <div class="w-[12rem] surface-popover-container">
                <button
                    onclick={() => setView("rows")}
                    class="surface-popover-button"
                >
                    <div class="size-3 flex-shrink-0">
                        {#if filesState.ui.fileViewType === "rows"}
                            <CircleIcon />
                        {/if}
                    </div>
                    <span>Rows</span>
                </button>
                <button
                    onclick={() => setView("grid")}
                    class="surface-popover-button"
                >
                    <div class="size-3 flex-shrink-0">
                        {#if filesState.ui.fileViewType === "grid"}
                            <CircleIcon />
                        {/if}
                    </div>
                    <span>Grid</span>
                </button>

                <hr class="basic-hr my-2" />

                <div class="px-2 py-1 text-[0.9rem] opacity-60">
                    Preview Size
                </div>

                {#each previewSizes as size}
                    <button
                        onclick={() => setPreviewSize(size)}
                        class="surface-popover-button"
                    >
                        <div class="size-3 flex-shrink-0">
                            {#if currentSize === size}
                                <CircleIcon />
                            {/if}
                        </div>
                        <span>{previewSizeLabels[size]}</span>
                    </button>
                {/each}
            </div>
        </Popover.Content>
    </Popover.Root>
{/if}