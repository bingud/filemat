<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import MenuIcon from "$lib/component/icons/MenuIcon.svelte";

</script>


<div class="w-full h-(--navbar-height) bg-neutral-900 flex justify-between items-center">
    <!-- Left side -->
    <div class="h-full">
        <button title="Toggle navigation menu" on:click={()=>{ uiState.menuOpen = true }} class="h-full py-3 px-3">
            <MenuIcon/>
        </button>
    </div>

    <!-- Right side -->
    <div></div>
</div>