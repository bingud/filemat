<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { onMount } from "svelte";
    import SymbolicLinksSetting from "./_setting-components/SymbolicLinksSetting.svelte";
    import ExposedFilesSetting from "./_setting-components/ExposedFilesSetting.svelte";
    import LogsSetting from "./_setting-components/LogsSetting.svelte";

    const title = "System Settings"

    onMount(() => {
        uiState.settings.title = title
    })
</script>


<div class="page settings-margin flex-col gap-8">
    <h2 class="text-lg font-medium">File System Settings</h2>
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
        
        <div class="flex flex-col gap-8 w-full">
            <SymbolicLinksSetting></SymbolicLinksSetting>
        </div>
    </div>

    <ExposedFilesSetting></ExposedFilesSetting>
    <LogsSetting></LogsSetting>
</div>