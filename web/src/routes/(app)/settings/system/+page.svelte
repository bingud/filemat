<script lang="ts">
    import { goto } from "$app/navigation";
    import { hasPermissionLevel } from "$lib/code/module/permissions";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { formData, handleError, handleErrorResponse, handleException, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";
    import SymbolicLinksSetting from "./_setting-components/SymbolicLinksSetting.svelte";
    import ExposedFilesSetting from "./_setting-components/ExposedFilesSetting.svelte";

    const title = "System Settings"

    onMount(() => {
        uiState.settings.title = title
        if (!hasPermissionLevel(3)) {
            goto(`/settings`)
            return
        }
    })
</script>


<div class="page flex-col gap-8">
    <h2 class="text-lg font-medium">File System Settings</h2>
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
        
        <div class="flex flex-col gap-8 w-full">
            <SymbolicLinksSetting></SymbolicLinksSetting>
        </div>
    </div>

    <ExposedFilesSetting></ExposedFilesSetting>
</div>