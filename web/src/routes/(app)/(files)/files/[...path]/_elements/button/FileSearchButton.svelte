<script>
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import MagnifyingGlassIcon from "$lib/component/icons/MagnifyingGlassIcon.svelte";
</script>


<button title="Search files" on:click={() => { filesState }} class="file-action-button">
    <MagnifyingGlassIcon />
</button>