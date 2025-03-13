<script lang="ts">
    import { uiState } from "$lib/code/state/uiState.svelte";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import MenuIcon from "$lib/component/icons/MenuIcon.svelte";

    function toggleMenu() {
        uiState.menuOpen = !uiState.menuOpen
    }

</script>


<div class="w-full h-(--navbar-height) bg-neutral-900 flex justify-between items-center">
    <!-- Left side -->
    <div class="h-full">
        <button title="Toggle navigation menu" on:click={toggleMenu} class="h-full py-3 px-3">
            {#if !uiState.menuOpen}
                <MenuIcon/>
            {:else}
                <CloseIcon />
            {/if}
        </button>
    </div>

    <!-- Right side -->
    <div></div>
</div>