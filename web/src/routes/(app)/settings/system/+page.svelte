<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { onMount, type Component } from "svelte";
    import SymbolicLinksSetting from "./_setting-components/SymbolicLinksSetting.svelte";
    import ExposedFilesSetting from "./_setting-components/ExposedFilesSetting.svelte";
    import LogsSetting from "./_setting-components/LogsSetting.svelte";
    import UploadFolderPathSetting from "./_setting-components/UploadFolderPathSetting.svelte";

    const title = "System Settings"

    onMount(() => {
        uiState.settings.title = title
    })
</script>


<div class="page !h-fit settings-margin flex-col gap-8 xoverflow-y-auto">
    <!-- <h2 class="text-lg font-medium">File Settings</h2> -->
     
    {@render settingCell(UploadFolderPathSetting)}
    {@render settingCell(SymbolicLinksSetting)}

    <ExposedFilesSetting></ExposedFilesSetting>
    <LogsSetting></LogsSetting>

</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-surface">
        <Component></Component>
    </div>
{/snippet}