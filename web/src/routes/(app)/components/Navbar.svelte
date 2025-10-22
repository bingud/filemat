<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { isFolder } from "$lib/code/util/codeUtil.svelte";
    import MenuIcon from "$lib/component/icons/MenuIcon.svelte";
    import FileDetailsButton from "../files/[...path]/_elements/button/FileDetailsButton.svelte";
    import NewFileButton from "../files/[...path]/_elements/button/NewFileButton.svelte";

</script>


<div class="w-full h-navbar bg-surface flex justify-between items-center">
    <!-- Left side -->
    <div class="h-full flex">
        <button title="Toggle navigation menu" on:click={()=>{ uiState.menuOpen = true }} class="h-full p-4">
            <MenuIcon/>
        </button>
        <div class="h-full flex gap-2 py-2">
            {#if !uiState.isDesktop && isFolder(filesState.data.currentMeta)}
                <NewFileButton></NewFileButton>
            {/if}
        </div>
    </div>

    <!-- Right side -->
    <div class="h-full flex gap-2 p-2">
        {#if !uiState.isDesktop}
            <FileDetailsButton></FileDetailsButton>
        {/if}
    </div>
</div>