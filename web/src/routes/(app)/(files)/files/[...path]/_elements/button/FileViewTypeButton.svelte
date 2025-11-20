<script>
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import GridIcon from "$lib/component/icons/GridIcon.svelte";
    import RowsIcon from "$lib/component/icons/RowsIcon.svelte";
    import { option_changeFileView } from "../../_code/pageLogic";

</script>

{#if filesState}
    <button on:click={option_changeFileView} title="Change file view" class="file-action-button">
        {#if filesState.ui.fileViewType === "rows"}
            <GridIcon />
        {:else}
            <RowsIcon />
        {/if}
    </button>
{/if}