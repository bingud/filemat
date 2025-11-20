<script>
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
</script>


<button title="Toggle the file details sidebar" on:click={() => { filesState.ui.toggleSidebar() }} class="file-action-button">
    <InfoIcon />
</button>