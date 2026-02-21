<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { type Component } from "svelte";
    import LogoutSetting from "./_setting-components/LogoutSetting.svelte";
    import MfaSetting from "./_setting-components/MfaSetting.svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import ChangePasswordSetting from "./_setting-components/ChangePasswordSetting.svelte";

    const title = "Account settings"

    $effect(() => {
        uiState.settings.title = title
        return appState.title.register(title) 
    })
</script>


<div class="page !h-fit settings-margin flex-col gap-8 xoverflow-y-auto">
    <!-- <h2 class="text-lg font-medium">Account Settings</h2> -->

    {@render settingCell(MfaSetting)}

    <ChangePasswordSetting />
    <LogoutSetting />
</div>

{#snippet settingCell(Component: Component<any>)}
    <div class="flex flex-col gap-6 p-6 rounded-lg w-full bg-surface">
        <Component></Component>
    </div>
{/snippet}